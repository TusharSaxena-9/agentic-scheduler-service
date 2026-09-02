package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionBatchItem;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionCandidate;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPassResult;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPendingQueue;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistribution;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionStatus;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.OrderAcceptanceView;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionAuditDao;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionFireLock;

import lombok.extern.slf4j.Slf4j;

/**
 * The Auto Job Distribution engine. Ports cron's whole {@code JobDistributionServiceImpl}
 * (SAAB-349): both entry points and the {@code selectAndFireNext()} critical section they share.
 *
 * <ul>
 *   <li><b>trigger</b> — {@link #triggerDistributionBatch(List)}, called in-process by the
 *       auto-assign workflow with the orders its gate rejected. Filters, enqueues, then opens the
 *       fire gate once.
 *   <li><b>advance</b> — {@link #processActiveDistributions()}, called by this job's own schedule.
 *       Evaluates live rounds, closes or escalates them, then opens the fire gate once.
 * </ul>
 *
 * <p>Both go through the same lock, which is the point: in cron the trigger half lived in {@code
 * AutoAssignComponent} and the two callers were in one JVM. After the migration they are one bean,
 * but a second worker pod would still put them in two processes.
 *
 * <p>What the loop does each pass: read every live round, decide its fate from a fresh writer read
 * of the order, close the ones that are done (accepted, cancelled, superseded, rescheduled, stale,
 * or out of escalation room), batch-fire the ones that should advance a round, and then give the
 * waiting pool one chance to start a new distribution.
 *
 * <p><b>The one-active-distribution invariant.</b> Only one distribution may be live system-wide,
 * so firing is gated: {@link #selectAndFireNext()} does nothing unless no round is currently
 * active. That check-then-fire is a read-then-act, and in cron it was made atomic by an in-JVM
 * {@code AtomicBoolean} justified as "sufficient because cron runs as a single instance". This
 * service cannot rely on that — see {@link DistributionFireLock} — so the section is wrapped in a
 * MySQL named lock instead, which also survives the eventual case of the trigger side running as a
 * second Temporal schedule on a different worker pod.
 *
 * <p><b>Nothing here is cached.</b> Booking times, reassign counts and the D-1 bypass set are all
 * re-read on every pass, at both the enqueue and the fire choke point. That is what lets a
 * newly-urgent order overtake older queued ones, and it is deliberate rather than incidental.
 */
@Slf4j
@Service
public class AutoJobDistributionService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final AutoJobDistributionReader reader;
  private final AutoJobDistributionWriter writer;
  private final DistributionAuditDao auditDao;
  private final DistributionAdminClient adminClient;
  private final DistributionFireLock fireLock;

  public AutoJobDistributionService(
      AutoJobDistributionReader reader,
      AutoJobDistributionWriter writer,
      DistributionAuditDao auditDao,
      DistributionAdminClient adminClient,
      DistributionFireLock fireLock) {
    this.reader = reader;
    this.writer = writer;
    this.auditDao = auditDao;
    this.adminClient = adminClient;
    this.fireLock = fireLock;
  }

  /**
   * One full pass: evaluate live rounds, then give the pending pool a chance.
   *
   * @return a summary of what the pass did, for the workflow to record
   */
  public DistributionPassResult processActiveDistributions(ProgressReporter progress) {
    List<JobDistribution> activeRounds = reader.activeRounds();

    if (activeRounds.isEmpty()) {
      // Nothing live — this is exactly the state the waiting pool needs checked, not only the case
      // where a round just went terminal below.
      boolean fired = selectAndFireNext();
      log.info("event=distribution_pass_completed active=0 fired_new={}", fired);
      return new DistributionPassResult(0, 0, 0, fired);
    }

    List<Long> orderIds =
        activeRounds.stream().map(JobDistribution::getOrderId).collect(Collectors.toList());
    Map<Long, OrderAcceptanceView> acceptanceByOrder = reader.acceptanceView(orderIds);

    long windowMillis = JobDistributionConstants.ROUND_WINDOW_MINUTES * 60_000L;
    long now = System.currentTimeMillis();

    List<DistributionBatchItem> advanceItems = new ArrayList<>();
    int closed = 0;

    int evaluated = 0;
    for (JobDistribution round : activeRounds) {
      try {
        if (evaluateRound(
            round, acceptanceByOrder.get(round.getOrderId()), windowMillis, now, advanceItems)) {
          closed++;
        }
      } catch (RuntimeException ex) {
        // Per-round isolation, as in the original: one unparseable order must not abandon the rest
        // of the pass, leaving their rounds live and un-evaluated.
        log.error(
            "event=distribution_round_evaluation_failed order_id={} round_id={} error={}",
            round.getOrderId(),
            round.getId(),
            ex.getMessage(),
            ex);
      }

      // One heartbeat per round evaluated. Each iteration can make an HTTP call to admin, so this
      // is what turns a hung socket into a failure in heartbeat-timeout seconds instead of holding
      // the slot for the whole startToClose budget — and, under SKIP overlap, silently swallowing
      // every tick that fires while it hangs.
      progress.report(++evaluated);
    }

    adminClient.fireBatch(advanceItems);

    // A round may have just reached a terminal state above, all of it already committed — so give
    // the waiting pool an immediate chance rather than idling until the next pass.
    boolean fired = selectAndFireNext();

    log.info(
        "event=distribution_pass_completed active={} closed={} advanced={} fired_new={}",
        activeRounds.size(),
        closed,
        advanceItems.size(),
        fired);
    return new DistributionPassResult(activeRounds.size(), closed, advanceItems.size(), fired);
  }

  /**
   * Decides one live round's fate. The branch order below is the original's and is significant:
   * acceptance wins over cancellation, cancellation over external assignment, and the order's own
   * slot having ended beats any judgement about the round's 5-minute window.
   *
   * @return {@code true} if the round was closed terminally
   */
  private boolean evaluateRound(
      JobDistribution round,
      OrderAcceptanceView acceptance,
      long windowMillis,
      long now,
      List<DistributionBatchItem> advanceItems) {

    Long orderId = round.getOrderId();
    if (acceptance == null) {
      return false;
    }

    if (Boolean.TRUE.equals(acceptance.getAccepted())) {
      writer.close(
          round, JobDistributionStatus.ACCEPTED, acceptance.getSpId(), acceptance.getIncentive());
      auditDao.audit(
          orderId,
          "Auto Job Distribution accepted by SP "
              + acceptance.getSpId()
              + " in round "
              + round.getRoundNumber()
              + "; Incentive: Rs. "
              + acceptance.getIncentive());
      return true;
    }

    if (acceptance.getOrderStatusCode() != null
        && JobDistributionConstants.CANCELLED_ORDER_STATUS_CODES.contains(
            acceptance.getOrderStatusCode())) {
      writer.close(round, JobDistributionStatus.CANCELLED, null, null);
      auditDao.audit(
          orderId,
          "Auto Job Distribution cancelled — order status changed to "
              + acceptance.getOrderStatusCode()
              + " during round "
              + round.getRoundNumber());
      return true;
    }

    // Assigned outside the distribution flow (e.g. a manual reassign) while this round was live.
    // Escalating would notify SPs for a job the accept endpoint would reject anyway, and would
    // eventually mislabel a perfectly-handled order as FINAL_UNASSIGNED.
    if (acceptance.getSpId() != null) {
      writer.close(round, JobDistributionStatus.SUPERSEDED, null, null);
      auditDao.audit(
          orderId,
          "Auto Job Distribution superseded — order assigned to SP "
              + acceptance.getSpId()
              + " outside the distribution flow during round "
              + round.getRoundNumber()
              + "; no further rounds.");
      return true;
    }

    // Rescheduled away from today/tomorrow: it re-enters the pipeline on whatever day it now sits,
    // always starting fresh at Round 1.
    if (acceptance.getBookingDate() != null) {
      LocalDate booking = acceptance.getBookingDate().toLocalDate();
      LocalDate today = LocalDate.now(IST);
      if (!booking.isEqual(today) && !booking.isEqual(today.plusDays(1))) {
        writer.close(round, JobDistributionStatus.RESCHEDULED, null, null);
        auditDao.audit(
            orderId,
            "Auto Job Distribution rescheduled — order's booking_date changed to "
                + acceptance.getBookingDate()
                + " during round "
                + round.getRoundNumber()
                + "; no further rounds today.");
        return true;
      }
    }

    // The order's own slot has already ended. This is what stops a round that sat live for hours
    // (because nothing was evaluating it) from then escalating through every remaining round for a
    // job whose time has passed.
    if (acceptance.getOrderEndTimeWithDate() != null
        && acceptance.getOrderEndTimeWithDate().getTime() <= now) {
      writer.close(round, JobDistributionStatus.EXPIRED, null, null);
      auditDao.audit(
          orderId,
          "Auto Job Distribution EXPIRED — order's slot (order_end_time_with_date="
              + acceptance.getOrderEndTimeWithDate()
              + ") had already passed at round "
              + round.getRoundNumber()
              + "; discarded, no further rounds.");
      return true;
    }

    boolean windowElapsed =
        round.getScheduledAt() != null && round.getScheduledAt().getTime() + windowMillis <= now;
    if (!windowElapsed) {
      return false;
    }

    return decideEscalation(round, advanceItems);
  }

  /**
   * The window elapsed with no acceptance — escalate, retry, or give up.
   *
   * <p>The incentive rises only when the round that just closed actually reached SPs. A round that
   * notified nobody retries at the <b>same</b> incentive, up to {@code MAX_CONSECUTIVE_ZERO_ROUNDS}
   * dry attempts in a row; a round that reaches at least one SP resets that streak. So the cap is
   * on consecutive dry rounds, not on total rounds. Separately, a round that did notify SPs at
   * {@code MAX_INCENTIVE} with no acceptance has nowhere left to go and ends the order.
   *
   * @return {@code true} if the round was closed terminally
   */
  private boolean decideEscalation(
      JobDistribution round, List<DistributionBatchItem> advanceItems) {
    Long orderId = round.getOrderId();
    Integer prevNotified = round.getSpNotifiedCount();
    boolean roundWasDry = prevNotified == null || prevNotified == 0;
    int currentIncentive =
        round.getIncentiveAmount() != null
            ? round.getIncentiveAmount()
            : JobDistributionConstants.BASE_INCENTIVE;

    if (roundWasDry) {
      int consecutiveDry = countConsecutiveZeroRounds(reader.notifiedCountsDesc(orderId));
      if (consecutiveDry >= JobDistributionConstants.MAX_CONSECUTIVE_ZERO_ROUNDS) {
        writer.close(round, JobDistributionStatus.FINAL_UNASSIGNED, null, null);
        auditDao.audit(
            orderId,
            "Auto Job Distribution FINAL_UNASSIGNED — no SP notified in "
                + consecutiveDry
                + " consecutive rounds at Rs. "
                + currentIncentive);
        return true;
      }
    } else if (currentIncentive >= JobDistributionConstants.MAX_INCENTIVE) {
      writer.close(round, JobDistributionStatus.FINAL_UNASSIGNED, null, null);
      auditDao.audit(
          orderId,
          "Auto Job Distribution FINAL_UNASSIGNED — round "
              + round.getRoundNumber()
              + " notified SPs at the maximum incentive (Rs. "
              + currentIncentive
              + ") with no acceptance; no further round to escalate to.");
      return true;
    }

    int nextIncentive =
        roundWasDry
            ? currentIncentive
            : Math.min(
                currentIncentive + JobDistributionConstants.INCENTIVE_STEP,
                JobDistributionConstants.MAX_INCENTIVE);

    // This service decides the round; admin executes the expire-and-insert.
    advanceItems.add(
        new DistributionBatchItem(
            orderId, round.getId(), round.getRoundNumber() + 1, nextIncentive));
    return false;
  }

  /**
   * The <b>trigger</b> entry point: takes the orders the auto-assign gate rejected, keeps the ones
   * that have genuinely earned a distribution, adds them to the waiting pool, and opens the fire
   * gate once.
   *
   * <p>Ported from cron's {@code JobDistributionServiceImpl#triggerDistributionBatch}, which
   * reached this service over HTTP ({@code POST /cron/trigger/job-distribution/trigger}) until this
   * migration. The hop is gone; the funnel it protected is not. Up to {@code BATCH_CHUNK_SIZE}
   * orders arrive and <b>at most one</b> is fired, because {@link #selectAndFireNext()} fires a
   * single order per gate opening and only while nothing is live. Everything else waits in the
   * queue and is re-ranked from scratch at the next opening.
   *
   * <p><b>Two independent bars, both applied here and again at fire time.</b> {@link
   * #isStillEligible} asks whether the order <i>can</i> still be distributed; {@link
   * #qualifiesForDistribution} asks whether it ever <i>earned</i> distribution. Re-checking both at
   * the fire choke point is what stops an order that reached the queue by some other route — or one
   * whose situation changed while it waited — from consuming a real round of SP notifications.
   *
   * <p>Safe to call again with the same ids: the enqueue is an {@code INSERT IGNORE} against {@code
   * uq_pending_order_active}, and the fire gate re-reads the live-round state under the lock. That
   * is what makes this usable directly as a Temporal activity body, where a retry after a partial
   * failure is normal.
   *
   * @param orderIds orders the auto-assign gate handed over; empty or null is a no-op
   * @return what the batch did, for the workflow's history
   */
  public DistributionTriggerResult triggerDistributionBatch(List<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return DistributionTriggerResult.empty();
    }

    List<DistributionCandidate> eligible = new ArrayList<>();
    for (DistributionCandidate candidate : reader.candidates(orderIds)) {
      if (passesTriggerExclusions(candidate)) {
        eligible.add(candidate);
      }
    }
    if (eligible.isEmpty()) {
      log.info(
          "event=distribution_trigger_batch received={} eligible=0 action=no_candidates",
          orderIds.size());
      return new DistributionTriggerResult(orderIds.size(), 0, 0, 0, 0);
    }

    List<Long> eligibleIds =
        eligible.stream().map(DistributionCandidate::getOrderId).collect(Collectors.toList());

    // Re-derived here rather than carried over from the auto-assign gate. That gate computed a
    // reassign count minutes ago on a different thread, and the manual-trigger path never computed
    // one at all — so the only trustworthy value is a fresh read, same reasoning as at fire time.
    Set<Long> blocked = reader.blockedOrderIds(eligibleIds);
    Map<Long, Integer> reassignCounts = reader.reassignCounts(eligibleIds);
    Set<Long> d1Bypass = reader.d1RejectionBypass(eligible);

    List<Long> toEnqueue = new ArrayList<>();
    for (DistributionCandidate candidate : eligible) {
      int reassignCount = reassignCounts.getOrDefault(candidate.getOrderId(), 0);
      if (!blocked.contains(candidate.getOrderId())
          && isStillEligible(candidate)
          && qualifiesForDistribution(candidate, reassignCount, d1Bypass)) {
        toEnqueue.add(candidate.getOrderId());
      }
    }

    int inserted = writer.enqueuePending(toEnqueue);

    // Opened once per batch, exactly as cron did. A shut gate is the normal case, not a failure:
    // it means a distribution is already live and these orders wait their turn.
    boolean fired = selectAndFireNext();

    DistributionTriggerResult result =
        new DistributionTriggerResult(
            orderIds.size(), eligible.size(), toEnqueue.size(), inserted, fired ? 1 : 0);
    log.info(
        "event=distribution_trigger_batch received={} eligible={} enqueued={} inserted={} fired={}",
        result.getReceived(),
        result.getEligible(),
        result.getEnqueued(),
        result.getInserted(),
        result.getFired());
    return result;
  }

  /**
   * The trigger-only exclusions: categories that never enter distribution, franchise hubs, and the
   * single-hub production gateway. Deliberately not re-checked at fire time — unlike the two
   * eligibility bars, these are properties of the order that cannot change while it waits, so
   * filtering once at the door is enough and the original did the same.
   *
   * <p>Each check mirrors cron's null handling exactly: a null category is never excluded, a null
   * hub is never a franchise, and while {@code RESTRICT_TO_SINGLE_HUB} is on an order with no hub
   * cannot match the restricted id and so falls through.
   */
  private boolean passesTriggerExclusions(DistributionCandidate candidate) {
    if (candidate.getOrderId() == null) {
      return false;
    }
    if (candidate.getCategoryId() != null
        && JobDistributionConstants.EXCLUDED_CATEGORY_IDS.contains(candidate.getCategoryId())) {
      return false;
    }
    if (JobDistributionConstants.RESTRICT_TO_SINGLE_HUB
        && !JobDistributionConstants.RESTRICTED_HUB_ID.equals(candidate.getHubId())) {
      return false;
    }
    return !Boolean.TRUE.equals(candidate.getHubIsFranchise());
  }

  /**
   * Fires the single most urgent waiting order, but only while nothing is active.
   *
   * <p>Wrapped in {@link DistributionFireLock} — the cross-process replacement for cron's in-JVM
   * flag. A pass that cannot take the lock simply does not fire; the next one re-checks.
   *
   * @return {@code true} if an order was fired
   */
  public boolean selectAndFireNext() {
    Boolean fired = fireLock.runIfAcquired(this::selectAndFireNextLocked);
    return Boolean.TRUE.equals(fired);
  }

  private boolean selectAndFireNextLocked() {
    if (!reader.activeRounds().isEmpty()) {
      return false; // something is live — wait for it to reach a terminal state
    }

    List<DistributionPendingQueue> pending = reader.pendingQueue();
    if (pending.isEmpty()) {
      return false;
    }

    Map<Long, DistributionPendingQueue> queueRowByOrderId = new HashMap<>();
    for (DistributionPendingQueue row : pending) {
      queueRowByOrderId.put(row.getOrderId(), row);
    }
    List<Long> orderIds = new ArrayList<>(queueRowByOrderId.keySet());

    Set<Long> blocked = reader.blockedOrderIds(orderIds);
    // Defensive copy: the sort below is in place, and since the read moved to
    // AutoJobDistributionReader
    // this list belongs to that bean. Sorting a caller's collection is an implicit contract that
    // holds
    // only while the reader happens to return a mutable ArrayList — it broke immediately against an
    // immutable list, which is exactly how it would break if the reader ever returned List.copyOf.
    List<DistributionCandidate> candidates = new ArrayList<>(reader.candidates(orderIds));
    Map<Long, Integer> reassignCounts = reader.reassignCounts(orderIds);
    Set<Long> d1Bypass = reader.d1RejectionBypass(candidates);

    candidates.sort(priorityComparator(reassignCounts));

    for (DistributionCandidate candidate : candidates) {
      DistributionPendingQueue row = queueRowByOrderId.get(candidate.getOrderId());
      if (row == null) {
        continue;
      }
      int reassignCount = reassignCounts.getOrDefault(candidate.getOrderId(), 0);

      if (blocked.contains(candidate.getOrderId())
          || !isStillEligible(candidate)
          || !qualifiesForDistribution(candidate, reassignCount, d1Bypass)) {
        // Stale, handled elsewhere, or never organically qualified. Id-scoped so a re-queued
        // order's earlier historical row is untouched.
        writer.discardPending(row.getId());
        continue;
      }

      DistributionBatchItem item =
          new DistributionBatchItem(
              candidate.getOrderId(), null, 1, JobDistributionConstants.BASE_INCENTIVE);

      if (adminClient.fireSingle(item)) {
        // Only on confirmed success. If this process dies between the fire and this write, the row
        // stays pending and is retried — safe, because admin's own uq_order_active dedupes a
        // duplicate fire for the same order into a no-op.
        writer.markPendingAsDistributed(row.getId());
        log.info(
            "event=distribution_fired order_id={} round=1 incentive={}",
            candidate.getOrderId(),
            JobDistributionConstants.BASE_INCENTIVE);
      }
      return true; // exactly one order per gate opening
    }
    return false;
  }

  /**
   * "Can this order still be distributed?" — unassigned, in a distributable status, not withdrawn
   * from the pool, enough runway left, and its slot not already over.
   */
  private boolean isStillEligible(DistributionCandidate order) {
    if (order.getSpAssignByUser() != null || order.getOrderStatusCode() == null) {
      return false;
    }
    if (Boolean.TRUE.equals(order.getUnassignWithRemoveReassigning())) {
      return false;
    }
    if (JobDistributionConstants.minutesUntilStart(order.getBookingDate(), order.getBookingTime())
        < JobDistributionConstants.MIN_MINUTES_BEFORE_START) {
      return false;
    }
    if (order.getOrderEndTimeWithDate() != null
        && order.getOrderEndTimeWithDate().getTime() <= System.currentTimeMillis()) {
      return false;
    }
    int status = order.getOrderStatusCode();
    return status == JobDistributionConstants.ORDER_NEW_REQUEST
        || status == JobDistributionConstants.ORDER_ASSIGN_TO_SERVICE_PROVIDER;
  }

  /**
   * "Did this order ever earn distribution?" — complementary to {@link #isStillEligible}, which
   * asks whether it still can be. Re-checked at fire time so an order that reached the queue by
   * some other route can never force its way into a round it never qualified for.
   */
  private boolean qualifiesForDistribution(
      DistributionCandidate order, int reassignCount, Set<Long> d1Bypass) {
    if (d1Bypass.contains(order.getOrderId())) {
      return true;
    }
    return reassignCount >= JobDistributionConstants.REASSIGN_THRESHOLD
        || JobDistributionConstants.minutesUntilStart(
                order.getBookingDate(), order.getBookingTime())
            < JobDistributionConstants.PRIORITY_TIME_THRESHOLD_MINUTES;
  }

  /**
   * Ranks waiting candidates. Two buckets, split at {@code PRIORITY_TIME_THRESHOLD_MINUTES}:
   *
   * <ul>
   *   <li>at or under 60 minutes to start — soonest start first, reassign count breaks ties
   *   <li>over 60 minutes — most-reassigned first, soonest start breaks ties
   * </ul>
   *
   * <p>So a genuinely time-critical order always jumps ahead of a merely long-queued one, while a
   * stalled order far from its slot still outranks a freshly queued peer. Order id is the final
   * tie-break, which makes the sort fully deterministic.
   */
  private Comparator<DistributionCandidate> priorityComparator(Map<Long, Integer> reassignCounts) {
    return (left, right) -> {
      long leftMinutes =
          JobDistributionConstants.minutesUntilStart(left.getBookingDate(), left.getBookingTime());
      long rightMinutes =
          JobDistributionConstants.minutesUntilStart(
              right.getBookingDate(), right.getBookingTime());
      boolean leftCritical =
          leftMinutes <= JobDistributionConstants.PRIORITY_TIME_THRESHOLD_MINUTES;
      boolean rightCritical =
          rightMinutes <= JobDistributionConstants.PRIORITY_TIME_THRESHOLD_MINUTES;

      if (leftCritical != rightCritical) {
        return leftCritical ? -1 : 1;
      }

      int leftReassign = reassignCounts.getOrDefault(left.getOrderId(), 0);
      int rightReassign = reassignCounts.getOrDefault(right.getOrderId(), 0);

      int result;
      if (leftCritical) {
        result = Long.compare(leftMinutes, rightMinutes);
        if (result == 0) {
          result = Integer.compare(rightReassign, leftReassign);
        }
      } else {
        result = Integer.compare(rightReassign, leftReassign);
        if (result == 0) {
          result = Long.compare(leftMinutes, rightMinutes);
        }
      }
      return result == 0 ? Long.compare(left.getOrderId(), right.getOrderId()) : result;
    };
  }

  /**
   * Counts how many of the most recent rounds notified zero SPs in a row, stopping at the first
   * that reached someone. That first non-dry round is where the incentive last escalated, so this
   * is equivalently "rounds since the incentive last changed".
   */
  static int countConsecutiveZeroRounds(List<Integer> notifiedCountsDesc) {
    int count = 0;
    for (Integer notified : notifiedCountsDesc) {
      if (notified == null || notified == 0) {
        count++;
      } else {
        break;
      }
    }
    return count;
  }
}
