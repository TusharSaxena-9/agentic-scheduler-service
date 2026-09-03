package com.yesmadam.schedulerservice.jobs.autoassign.service;

import static com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignConfig.EXPRESS_MIN_MINUTES_BEFORE_START;
import static com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignConfig.EXPRESS_ORDER_AGE_LOOKBACK_MINUTES;
import static com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignConfig.MIN_LEAD_TIME_MINUTES;
import static com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignConfig.PRIORITY_TIME_THRESHOLD_MINUTES;
import static com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignConfig.REASSIGN_THRESHOLD;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.yesmadam.enumu.OrderStatus;
import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.service.AutoJobDistributionService;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignConfig;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignOutcome;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignCandidate;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignRunContext;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignThresholds;
import com.yesmadam.schedulerservice.jobs.autoassign.projections.UnassignedOrderProjection;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;
import com.yesmadam.schedulerservice.utils.BookingSlotTimeUtil;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

/**
 * Auto-assign business logic, ported from cron's {@code
 * AutoAssignComponent#findNonAssignJobAndAssignAServiceProviderToJob}.
 *
 * <p>Same decision, same order: an unassigned same-day order is auto-assigned while no SP has ever
 * accepted-then-cancelled it, its reassign attempts are still under the threshold, and it is not
 * about to start. Anything failing one of those goes to Auto Job Distribution instead — except a
 * fresh, express-flagged order about to start, which bypasses those checks as a last resort. See
 * {@link #shouldAutoAssign}.
 *
 * <p>That handoff used to be an HTTP POST to cron. The distribution engine now lives in this
 * service, so it is a direct call to {@link AutoJobDistributionService#triggerDistributionBatch} —
 * same filters, same waiting pool, same one-order-at-a-time fire gate, one less network hop and one
 * less service that has to be up for this run to finish.
 */
@Slf4j
@Service
public class AutoAssignServiceImpl implements AutoAssignService {

  /** Statuses that mean the order is still waiting on an SP. */
  private static final List<Integer> UNASSIGNED_ORDER_STATUS_CODES =
      List.of(
          OrderStatus.ORDER_NEW_REQUEST.getStatus(),
          OrderStatus.ORDER_ASSIGN_TO_SERVICE_PROVIDER.getStatus());

  /**
   * Treated as the minutes-to-start of an order whose slot label could not be parsed, so it reads
   * as "starts infinitely far away" and stays on the auto-assign path rather than being
   * force-queued for distribution on a parse error — the cron's parse-failure-is-safe behaviour.
   */
  private static final long UNKNOWN_MINUTES_UNTIL_START = Long.MAX_VALUE;

  /**
   * Treated as the minutes-since-created of an order whose {@code created_at} could not be
   * resolved. Unlike {@link #UNKNOWN_MINUTES_UNTIL_START}, this fails its check closed rather than
   * open: unknown creation time must never unlock the express last-resort bypass, so "infinitely
   * long ago" is deliberately the unsafe direction here, not the safe one.
   */
  private static final long UNKNOWN_MINUTES_SINCE_CREATED = Long.MAX_VALUE;

  private final AutoAssignOrderReader autoAssignOrderReader;
  private final SettingService settingService;
  private final AutoAssignApiClient autoAssignApiClient;
  private final AutoJobDistributionService autoJobDistributionService;

  public AutoAssignServiceImpl(
      AutoAssignOrderReader autoAssignOrderReader,
      SettingService settingService,
      AutoAssignApiClient autoAssignApiClient,
      AutoJobDistributionService autoJobDistributionService) {
    this.autoAssignOrderReader = autoAssignOrderReader;
    this.settingService = settingService;
    this.autoAssignApiClient = autoAssignApiClient;
    this.autoJobDistributionService = autoJobDistributionService;
  }

  @Override
  public AutoAssignRunContext fetchRunContext(AutoAssignScope scope) {
    Map<String, SettingsForEnalbeDisable> config =
        settingService.findSettingWithKeys(AutoAssignConfig.keys());

    AutoAssignThresholds thresholds =
        new AutoAssignThresholds(
            REASSIGN_THRESHOLD.intValue(config),
            PRIORITY_TIME_THRESHOLD_MINUTES.intValue(config),
            MIN_LEAD_TIME_MINUTES.intValue(config),
            EXPRESS_MIN_MINUTES_BEFORE_START.intValue(config),
            EXPRESS_ORDER_AGE_LOOKBACK_MINUTES.intValue(config));

    LocalDate bookingDate = LocalDate.now().plusDays(scope.getDayOffset());
    Timestamp minStartTime =
        Timestamp.valueOf(LocalDateTime.now().plusMinutes(thresholds.getMinLeadTimeMinutes()));

    List<UnassignedOrderProjection> rows =
        autoAssignOrderReader.findUnassignedOrders(
            scope, UNASSIGNED_ORDER_STATUS_CODES, Date.valueOf(bookingDate), minStartTime);

    List<AutoAssignCandidate> candidates =
        rows.stream()
            .map(
                row ->
                    new AutoAssignCandidate(
                        row.getOrderId(),
                        BookingSlotTimeUtil.startTimeMillis(
                            row.getBookingDate(), row.getBookingTime()),
                        Boolean.TRUE.equals(row.getIsExpressJob()),
                        row.getCreatedAt() == null ? null : row.getCreatedAt().getTime()))
            .collect(Collectors.toList());

    log.info(
        "event=auto_assign_run_context scope={} booking_date={} candidates={} reassign_threshold={}"
            + " priority_window_min={} min_lead_time_min={}",
        scope,
        bookingDate,
        candidates.size(),
        thresholds.getReassignThreshold(),
        thresholds.getPriorityTimeThresholdMinutes(),
        thresholds.getMinLeadTimeMinutes());

    return new AutoAssignRunContext(bookingDate.toString(), candidates, thresholds, scope);
  }

  @Override
  public AutoAssignChunkResult processChunk(
      List<AutoAssignCandidate> chunk,
      AutoAssignThresholds thresholds,
      AutoAssignScope scope,
      ProgressReporter progress) {

    if (chunk == null || chunk.isEmpty()) {
      return AutoAssignChunkResult.empty();
    }

    List<Long> orderIds =
        chunk.stream().map(AutoAssignCandidate::getOrderId).collect(Collectors.toList());

    // Both reads are batched per chunk rather than once for the whole run: the IN clause stays
    // bounded, and each chunk's reads are retried together with the chunk that used them. Each call
    // is timed and its row count captured, so the breakdown rides along in this activity's own
    // result — visible in the Temporal dashboard once the activity completes.
    QueryTimer timer = new QueryTimer();
    Map<Long, Integer> reassignCounts =
        timer.time("findReassignCounts", () -> autoAssignOrderReader.findReassignCounts(orderIds));
    Set<Long> acceptedThenCancelled =
        new HashSet<>(
            timer.time(
                "findAcceptedDistributionOrderIds",
                () -> autoAssignOrderReader.findAcceptedDistributionOrderIds(orderIds)));
    // Slot starts are re-read here, not taken from the run context. See minutesUntilStart.
    Map<Long, Long> freshStartMillis =
        timer.time(
            "findSlotStartMillis", () -> autoAssignOrderReader.findSlotStartMillis(orderIds));
    // D-1 only. Not issued at all for a same-day run, matching the cron: its D0 method never
    // computes this set, so a same-day run must not pay for the query either.
    Set<Long> spRejectedAfterCutoff =
        scope.isSpRejectionBypassApplied()
            ? new HashSet<>(
                timer.time(
                    "findSpRejectedAfterCutoff",
                    () ->
                        autoAssignOrderReader.findSpRejectedAfterCutoff(
                            orderIds, todaysSpRejectionCutoff())))
            : Collections.emptySet();

    Map<AutoAssignOutcome, Integer> outcomeCounts = new EnumMap<>(AutoAssignOutcome.class);
    List<Long> distributionOrderIds = new ArrayList<>();

    int completed = 0;
    for (AutoAssignCandidate candidate : chunk) {
      Long orderId = candidate.getOrderId();
      int reassignCount = reassignCounts.getOrDefault(orderId, 0);

      if (shouldAutoAssign(
          candidate,
          reassignCount,
          acceptedThenCancelled,
          spRejectedAfterCutoff,
          thresholds,
          freshStartMillis,
          scope)) {
        boolean accepted = autoAssignApiClient.triggerAssign(orderId);
        record(
            outcomeCounts,
            accepted ? AutoAssignOutcome.ASSIGN_TRIGGERED : AutoAssignOutcome.ASSIGN_FAILED);
      } else {
        // Reassign attempts exhausted, an SP accepted then cancelled, the job starts too soon, or
        // (D-1 only) an SP rejected it after today's cutoff.
        distributionOrderIds.add(orderId);
        record(outcomeCounts, AutoAssignOutcome.QUEUED_FOR_DISTRIBUTION);
      }

      // Per order, not per chunk: the assign call is the only slow step here, so a heartbeat after
      // each one is what distinguishes "working through the chunk" from "wedged on order 7".
      progress.report(++completed);
    }

    log.info(
        "event=auto_assign_chunk_processed scope={} evaluated={} triggered={} failed={}"
            + " queued_for_distribution={}",
        scope,
        chunk.size(),
        outcomeCounts.getOrDefault(AutoAssignOutcome.ASSIGN_TRIGGERED, 0),
        outcomeCounts.getOrDefault(AutoAssignOutcome.ASSIGN_FAILED, 0),
        outcomeCounts.getOrDefault(AutoAssignOutcome.QUEUED_FOR_DISTRIBUTION, 0));

    return new AutoAssignChunkResult(
        chunk.size(),
        outcomeCounts,
        distributionOrderIds,
        timer.getDurationsMs(),
        timer.getRowCounts());
  }

  @Override
  public DistributionTriggerResult dispatchDistributionBatch(List<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return DistributionTriggerResult.empty();
    }
    return autoJobDistributionService.triggerDistributionBatch(orderIds);
  }

  /**
   * The auto-assign gate. An order is auto-assigned when either term below holds:
   *
   * <ul>
   *   <li>{@code safeToReassign} — unchanged from the original port: no SP has
   *       accepted-then-cancelled the order, no SP rejected it after today's cutoff (D-1 only), its
   *       reassign attempts are still under the threshold, and its slot is not inside the priority
   *       window.
   *   <li>{@code expressLastResort} — {@link AutoAssignScope#SAME_DAY} only: the order is flagged
   *       {@code is_express_job}, about to start, and was created recently. This term was missing
   *       entirely from the original port; {@code AutoAssignOrderRepository
   *       #findUnassignedOrdersForBookingDate} had to change alongside it, since its old lead-time
   *       floor excluded these orders from the candidate list before this gate ever saw them.
   * </ul>
   *
   * <p>The two terms are independent, not layered: {@code expressLastResort} deliberately bypasses
   * the reassign-count and accepted-then-cancelled checks too, matching cron exactly. It is a true
   * last resort, not an extra condition stacked on top of the normal gate.
   *
   * <p>The {@code spRejectedAfterCutoff} term is the one difference between the two source crons'
   * original gates. It is always empty for {@link AutoAssignScope#SAME_DAY}, so the same expression
   * serves both without a branch — the D0 cron simply never had a set to test against.
   *
   * <p>Note the polarity, which is easy to read backwards: being <em>in</em> the bypass set means
   * the order is diverted <em>away</em> from auto-assign and into distribution. The distribution
   * engine's own {@code qualifiesForDistribution} then treats membership in the same set as grounds
   * to <em>admit</em> the order despite a sub-threshold reassign count. Divert here, admit there —
   * the two together are what let a rejected D-1 order skip the reassign ladder entirely.
   */
  private boolean shouldAutoAssign(
      AutoAssignCandidate candidate,
      int reassignCount,
      Set<Long> acceptedThenCancelled,
      Set<Long> spRejectedAfterCutoff,
      AutoAssignThresholds thresholds,
      Map<Long, Long> freshStartMillis,
      AutoAssignScope scope) {

    boolean safeToReassign =
        !acceptedThenCancelled.contains(candidate.getOrderId())
            && !spRejectedAfterCutoff.contains(candidate.getOrderId())
            && reassignCount < thresholds.getReassignThreshold()
            && minutesUntilStart(candidate, freshStartMillis)
                >= thresholds.getPriorityTimeThresholdMinutes();

    boolean expressLastResort =
        scope.isExpressBypassApplied()
            && candidate.isExpressJob()
            && minutesUntilStart(candidate, freshStartMillis)
                < thresholds.getExpressMinMinutesBeforeStart()
            && minutesSinceCreated(candidate) < thresholds.getExpressOrderAgeLookbackMinutes();

    return safeToReassign || expressLastResort;
  }

  /**
   * Today's SP-rejection cutoff instant for the D-1 bypass, recomputed per chunk and never cached.
   *
   * <p>Deliberately delegates to the distribution engine's constant rather than defining a second
   * one: the gate here decides which orders are diverted, and the engine's {@code
   * qualifiesForDistribution} decides which of those it admits. Two copies of the hour could drift
   * apart and leave orders diverted by one rule and rejected by the other, which would strand them.
   */
  private static Timestamp todaysSpRejectionCutoff() {
    return JobDistributionConstants.todaysD1RejectionBypassCutoff();
  }

  /**
   * Minutes from now until the order's slot starts — negative if already past, {@link
   * #UNKNOWN_MINUTES_UNTIL_START} when the slot start could not be resolved.
   *
   * <p><b>Both halves of this are re-evaluated per chunk, and both matter.</b> "Now" is read on the
   * activity thread, and the slot start comes from {@code freshStartMillis} — a per-chunk re-read
   * of {@code booking_date}/{@code booking_time} — rather than from the value the run context
   * resolved when the workflow began. Chunks execute minutes apart, so the context's copy can be
   * stale in two ways: time has simply passed, and the order may have been rescheduled outright.
   *
   * <p>An order absent from {@code freshStartMillis} was not returned by the re-read (deleted or
   * otherwise gone mid-run); the context value is used rather than treating it as unknown, which is
   * the more conservative of the two. An order present with a {@code null} value has a slot label
   * that will not parse, and reads as "starts infinitely far away" — the cron's
   * parse-failure-is-safe behaviour, which keeps it on the auto-assign path rather than
   * force-queuing it for distribution on a parse error.
   *
   * <p>The cron needed none of this: it evaluated every order within milliseconds of its own fetch,
   * so re-reading would have returned what it already had. The consequence is a small, deliberate
   * divergence — an order whose runway falls below the priority window mid-run is diverted to
   * distribution here, where cron would have auto-assigned it this tick and diverted it on the next
   * one. The engine's own {@code qualifiesForDistribution} rule agrees that such an order belongs
   * in distribution, so this is the same decision, at most one tick earlier.
   */
  private long minutesUntilStart(AutoAssignCandidate candidate, Map<Long, Long> freshStartMillis) {
    Long startMillis =
        freshStartMillis.containsKey(candidate.getOrderId())
            ? freshStartMillis.get(candidate.getOrderId())
            : candidate.getStartTimeMillis();
    if (startMillis == null) {
      return UNKNOWN_MINUTES_UNTIL_START;
    }
    return (startMillis - System.currentTimeMillis()) / 60_000L;
  }

  /**
   * Minutes since the order was created, or {@link #UNKNOWN_MINUTES_SINCE_CREATED} when {@code
   * created_at} could not be resolved for this candidate.
   *
   * <p>Unlike {@link #minutesUntilStart}, this is not re-read per chunk: {@code created_at} is
   * immutable once written, so the value the run context resolved at fetch time can never go stale
   * the way a booking slot can.
   */
  private long minutesSinceCreated(AutoAssignCandidate candidate) {
    Long createdAtMillis = candidate.getCreatedAtMillis();
    if (createdAtMillis == null) {
      return UNKNOWN_MINUTES_SINCE_CREATED;
    }
    return (System.currentTimeMillis() - createdAtMillis) / 60_000L;
  }

  private static void record(
      Map<AutoAssignOutcome, Integer> outcomeCounts, AutoAssignOutcome outcome) {
    outcomeCounts.merge(outcome, 1, Integer::sum);
  }
}
