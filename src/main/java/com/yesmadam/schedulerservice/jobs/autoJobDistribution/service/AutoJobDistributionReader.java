package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionCandidate;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPendingQueue;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistribution;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.OrderAcceptanceView;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.projections.DistributionCandidateProjection;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.projections.OrderAcceptanceProjection;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionOrderRepository;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionPendingQueueRepository;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.JobDistributionRepository;

/**
 * Every read the distribution engine performs, on its own bean.
 *
 * <p><b>Why a separate bean and not methods on the service.</b> {@code @Transactional} is applied
 * by a Spring proxy, and a call from one method to another <em>on the same bean</em> does not pass
 * through that proxy. These reads used to live on {@code AutoJobDistributionService} and were
 * called from {@code triggerDistributionBatch} and {@code selectAndFireNextLocked} on that same
 * instance — so none of their annotations had any effect. They ran with no transaction at all,
 * which routes to the <b>writer</b>. Moving them here makes every call cross the proxy boundary, so
 * the routing below is real rather than decorative (rule 13).
 *
 * <p><b>Two routing groups, and the split is deliberate.</b> Most reads here are historical and
 * belong on the replica. Three do not, and are marked {@code @Transactional} without {@code
 * readOnly} precisely so they land on the writer — each one is a read-after-write where replica lag
 * would not merely be stale, it would change the decision:
 *
 * <ul>
 *   <li>{@link #acceptanceView(List)} — admin commits the SP's acceptance moments before the
 *       advance loop reads it. Stale here means escalating a round somebody has already taken, and
 *       notifying more SPs for a job that is gone.
 *   <li>{@link #candidates(List)} — the fire-time re-validation. Stale here means firing a fresh
 *       round for an order that was assigned seconds ago.
 *   <li>{@link #pendingQueue()} — the trigger side enqueues and then immediately selects, in one
 *       pass. A lagging replica would simply not see the rows just written and the gate would fire
 *       nothing, every time.
 * </ul>
 *
 * <p>Each read is a single repository call, so the transaction it opens is as short as the
 * statement — which is what rule 13's connection arithmetic needs. Nothing here computes, sorts or
 * formats; that all happens in the service, outside any transaction.
 */
@Service
public class AutoJobDistributionReader {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final JobDistributionRepository jobDistributionRepository;
  private final DistributionPendingQueueRepository pendingQueueRepository;
  private final DistributionOrderRepository orderRepository;

  public AutoJobDistributionReader(
      JobDistributionRepository jobDistributionRepository,
      DistributionPendingQueueRepository pendingQueueRepository,
      DistributionOrderRepository orderRepository) {
    this.jobDistributionRepository = jobDistributionRepository;
    this.pendingQueueRepository = pendingQueueRepository;
    this.orderRepository = orderRepository;
  }

  // ================================================================
  // Replica reads — historical rows, or rows written by another service well before this pass
  // ================================================================

  /**
   * Live rounds for the advance loop to evaluate.
   *
   * <p><b>Access path:</b> {@code idx_active_status (is_active, status)} on {@code
   * tbl_job_distribution} — an equality lookup on both columns, so a covering range scan over a
   * handful of rows. The one-active-distribution invariant keeps the result at ~1 row.
   */
  @Transactional(readOnly = true)
  public List<JobDistribution> activeRounds() {
    return jobDistributionRepository.findActiveDistributed();
  }

  /**
   * Orders already carrying a live round, or a FINAL_UNASSIGNED round created today — the
   * re-trigger guard.
   *
   * <p>Replica-safe: these rows are historical by the time the guard reads them, and a round
   * created by this same pass has not been written yet.
   *
   * <p><b>Access path:</b> {@code idx_order_created (order_id, created_at)} on {@code
   * tbl_job_distribution} — range on the leading {@code order_id IN (...)}, with {@code created_at}
   * filtered from the same index entry.
   */
  @Transactional(readOnly = true)
  public Set<Long> blockedOrderIds(List<Long> orderIds) {
    Timestamp startOfDay = Timestamp.valueOf(LocalDate.now(IST).atStartOfDay());
    return new HashSet<>(jobDistributionRepository.findBlockedOrderIds(orderIds, startOfDay));
  }

  /**
   * Reassign attempts per order, off {@code tbl_order_history}.
   *
   * <p><b>Access path:</b> {@code idx_order_id} on {@code ysmdm_users.tbl_order_history}. Verified
   * on UAT: {@code type=range, key=idx_order_id, rows=7, Using index condition} for a five-id list,
   * so the {@code GROUP BY} aggregates only the matched orders' own history rows.
   *
   * <p>Both columns arrive as {@link Number} and are narrowed here: the query is native, so MySQL
   * decides the Java types, and {@code SUM()} returns {@code BigDecimal}.
   */
  @Transactional(readOnly = true)
  public Map<Long, Integer> reassignCounts(List<Long> orderIds) {
    Map<Long, Integer> counts = new HashMap<>();
    for (Object[] row : orderRepository.findReassignCounts(orderIds)) {
      if (row.length >= 2 && row[0] != null) {
        counts.put(
            ((Number) row[0]).longValue(), row[1] == null ? 0 : ((Number) row[1]).intValue());
      }
    }
    return counts;
  }

  /**
   * SP-notified counts for every round an order has had, most recent first — feeds the
   * consecutive-dry-round streak that decides FINAL_UNASSIGNED.
   *
   * <p><b>Access path:</b> {@code idx_order_created (order_id, created_at)} on {@code
   * tbl_job_distribution} for the {@code order_id} equality; the {@code ORDER BY round_number DESC}
   * is not covered by that index, so MySQL sorts the matched rows. Bounded by rounds-per-order,
   * which the consecutive-dry cap keeps in single digits.
   */
  @Transactional(readOnly = true)
  public List<Integer> notifiedCountsDesc(Long orderId) {
    return jobDistributionRepository.findNotifiedCountsDesc(orderId);
  }

  /**
   * D-1 only: tomorrow's orders whose assigned SP rejected them after today's cutoff. Scoped to
   * tomorrow's bookings first — the bypass must never apply to same-day or future-dated orders.
   *
   * <p><b>Access path:</b> {@code idx_order_id} on {@code tbl_order_history}, same plan as {@link
   * #reassignCounts(List)}. The {@code message LIKE '%...%'} has a leading wildcard and cannot use
   * an index, but {@code order_id IN (...)} is applied first, so the {@code LIKE} only ever scans
   * the matched orders' own rows — measured at 7 examined rows for a five-id list, not a table
   * scan.
   */
  @Transactional(readOnly = true)
  public Set<Long> d1RejectionBypass(List<DistributionCandidate> candidates) {
    List<Long> tomorrowIds =
        candidates.stream()
            .filter(c -> JobDistributionConstants.isTomorrowBooking(c.getBookingDate()))
            .map(DistributionCandidate::getOrderId)
            .collect(Collectors.toList());
    if (tomorrowIds.isEmpty()) {
      return new HashSet<>();
    }
    return new HashSet<>(
        orderRepository.findSpRejectedAfterCutoff(
            tomorrowIds, JobDistributionConstants.todaysD1RejectionBypassCutoff()));
  }

  // ================================================================
  // Writer reads — deliberately NOT readOnly. See the class note for why each one has to be.
  // ================================================================

  /**
   * The batched acceptance read that drives every branch of the advance loop.
   *
   * <p><b>Writer, deliberately.</b> Admin commits the acceptance moments before this executes, so
   * on a lagging replica an already-accepted round reads as still open and the loop escalates it —
   * notifying more SPs for a job somebody has already taken. Do not add {@code readOnly = true}
   * here.
   *
   * <p><b>Access path:</b> {@code PRIMARY} on {@code ysmdm_users.tbl_order} — {@code order_id IN
   * (...)} is a primary-key range. Verified on UAT as {@code type=range, key=PRIMARY}.
   */
  @Transactional
  public Map<Long, OrderAcceptanceView> acceptanceView(List<Long> orderIds) {
    Map<Long, OrderAcceptanceView> byOrderId = new HashMap<>();
    for (OrderAcceptanceProjection row : orderRepository.findAcceptanceView(orderIds)) {
      byOrderId.put(
          row.getOrderId(),
          new OrderAcceptanceView(
              toBoolean(row.getAccepted()),
              row.getSpId(),
              row.getIncentive(),
              row.getOrderStatusCode(),
              row.getOrderEndTimeWithDate(),
              row.getBookingDate()));
    }
    return byOrderId;
  }

  /**
   * Narrows the acceptance flag the driver returns. Null stays null: an order never offered through
   * distribution has no answer, which the advance loop treats differently from an explicit "no".
   */
  private static Boolean toBoolean(Number flag) {
    return flag == null ? null : flag.intValue() != 0;
  }

  /**
   * Everything needed to filter, re-validate and rank an order, at both choke points.
   *
   * <p><b>Writer, deliberately.</b> This is the re-validation immediately before firing. An assign
   * that admin committed seconds ago must be visible, or the gate fires a fresh round for an order
   * that already has an SP. Do not add {@code readOnly = true} here.
   *
   * <p><b>Access path:</b> verified on UAT — {@code o: type=range, key=PRIMARY} for the {@code
   * order_id IN (...)}, and {@code h: type=eq_ref, key=PRIMARY} for the {@code LEFT JOIN
   * ysmdm_admin.tbl_hub h ON h.id = o.hub_id}. Both sides are primary-key access; the join adds one
   * row lookup per order and replaces what would otherwise be an N+1 of point reads.
   */
  @Transactional
  public List<DistributionCandidate> candidates(List<Long> orderIds) {
    List<DistributionCandidate> candidates = new ArrayList<>();
    for (DistributionCandidateProjection row : orderRepository.findCandidates(orderIds)) {
      candidates.add(
          new DistributionCandidate(
              row.getOrderId(),
              row.getSpAssignByUser(),
              row.getOrderStatusCode(),
              row.getBookingDate(),
              row.getBookingTime(),
              row.getOrderEndTimeWithDate(),
              toBoolean(row.getUnassignWithRemoveReassigning()),
              row.getCategoryId(),
              row.getHubId(),
              toBoolean(row.getHubIsFranchise())));
    }
    return candidates;
  }

  /**
   * Everything currently waiting to be fired.
   *
   * <p><b>Writer, deliberately.</b> The trigger side enqueues and then immediately selects within
   * one pass, so a lagging replica would not see the rows just written and the gate would fire
   * nothing every time. Do not add {@code readOnly = true} here.
   *
   * <p><b>Access path:</b> {@code idx_is_pending (is_pending)} on {@code
   * tbl_distribution_pending_queue} — equality on {@code is_pending = 1}. The waiting set is small
   * by construction: one order fires at a time, so the queue drains rather than accumulating.
   */
  @Transactional
  public List<DistributionPendingQueue> pendingQueue() {
    return pendingQueueRepository.findPending();
  }
}
