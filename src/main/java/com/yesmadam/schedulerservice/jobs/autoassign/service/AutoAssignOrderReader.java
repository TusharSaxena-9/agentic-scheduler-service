package com.yesmadam.schedulerservice.jobs.autoassign.service;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.projections.UnassignedOrderProjection;
import com.yesmadam.schedulerservice.jobs.autoassign.repository.AutoAssignOrderRepository;
import com.yesmadam.schedulerservice.utils.BookingSlotTimeUtil;

/**
 * Read-only side of the auto-assign run.
 *
 * <p>Kept as a separate bean so every {@code @Transactional(readOnly = true)} boundary goes through
 * the Spring proxy — as methods on {@link AutoAssignServiceImpl} the self-invocation would silently
 * bypass transaction management, and with it {@code ReadWriteRoutingDataSource}, sending these
 * reads to the writer pool instead of the replica.
 *
 * <p>Every method that narrows a numeric column does so explicitly, through {@link Number}. The
 * queries behind them are native, and MySQL yields {@code BigInteger} for {@code BIGINT UNSIGNED}
 * and {@code BigDecimal} for an aggregate — so leaving the narrowing to a projection proxy's
 * conversion service would be a runtime bet that compiles cleanly and fails on the first real row.
 */
@Service
public class AutoAssignOrderReader {

  private final AutoAssignOrderRepository autoAssignOrderRepository;

  public AutoAssignOrderReader(AutoAssignOrderRepository autoAssignOrderRepository) {
    this.autoAssignOrderRepository = autoAssignOrderRepository;
  }

  /**
   * Unassigned orders on {@code bookingDate}, with or without a lead-time floor depending on {@code
   * scope}.
   *
   * <p>The two variants are separate queries rather than one with a nullable parameter — see {@code
   * AutoAssignOrderRepository}. {@code minStartTime} is ignored for a scope that applies no floor,
   * so callers may pass whatever they computed without branching.
   */
  @Transactional(readOnly = true)
  public List<UnassignedOrderProjection> findUnassignedOrders(
      AutoAssignScope scope, List<Integer> statusCodes, Date bookingDate, Timestamp minStartTime) {
    List<UnassignedOrderProjection> rows =
        scope.isLeadTimeFloorApplied()
            ? autoAssignOrderRepository.findUnassignedOrdersForBookingDate(
                statusCodes, bookingDate, minStartTime)
            : autoAssignOrderRepository.findUnassignedOrdersForBookingDateWithoutLeadTime(
                statusCodes, bookingDate);
    return rows == null ? Collections.emptyList() : rows;
  }

  /**
   * Orders whose assigned SP rejected them after {@code cutoff} — the D-1-only bypass set.
   *
   * <p>Read per chunk rather than once per run, matching how reassign counts and slot times are
   * handled here: a rejection landing mid-run is then seen by the chunks that follow. The cron
   * computed it once up front, so this is strictly fresher, never staler.
   */
  @Transactional(readOnly = true)
  public List<Long> findSpRejectedAfterCutoff(List<Long> orderIds, Timestamp cutoff) {
    List<Number> rows = autoAssignOrderRepository.findSpRejectedAfterCutoff(orderIds, cutoff);
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyList();
    }
    return rows.stream()
        .filter(Objects::nonNull)
        .map(Number::longValue)
        .collect(Collectors.toList());
  }

  /**
   * Reassign attempts per order id; orders with no history rows are absent, not zero-valued.
   *
   * <p>Both columns arrive as {@link Number} and are narrowed here — see the class note.
   */
  @Transactional(readOnly = true)
  public Map<Long, Integer> findReassignCounts(List<Long> orderIds) {
    List<Object[]> rows = autoAssignOrderRepository.findReassignCounts(orderIds);
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Long, Integer> counts = new HashMap<>();
    for (Object[] row : rows) {
      if (row == null || row.length < 2 || row[0] == null) {
        continue;
      }
      counts.put(((Number) row[0]).longValue(), row[1] == null ? 0 : ((Number) row[1]).intValue());
    }
    return counts;
  }

  /** Order ids that already had a distribution round accepted by an SP. */
  @Transactional(readOnly = true)
  public List<Long> findAcceptedDistributionOrderIds(List<Long> orderIds) {
    List<Number> rows = autoAssignOrderRepository.findAcceptedDistributionOrderIds(orderIds);
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyList();
    }
    return rows.stream()
        .filter(Objects::nonNull)
        .map(Number::longValue)
        .collect(Collectors.toList());
  }

  /**
   * Each order's slot start as epoch millis, resolved from the live {@code booking_date} and {@code
   * booking_time} rather than from whatever the run context recorded minutes earlier.
   *
   * <p>Orders whose slot label cannot be parsed map to {@code null}, which the gate reads as
   * "starts infinitely far away" — the cron's parse-failure-is-safe behaviour. An order missing
   * from the result at all (deleted mid-run) is simply absent, and the caller falls back to the
   * context value.
   */
  @Transactional(readOnly = true)
  public Map<Long, Long> findSlotStartMillis(List<Long> orderIds) {
    List<UnassignedOrderProjection> rows = autoAssignOrderRepository.findSlotTimes(orderIds);
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Long, Long> startMillisByOrderId = new HashMap<>();
    for (UnassignedOrderProjection row : rows) {
      if (row.getOrderId() == null) {
        continue;
      }
      startMillisByOrderId.put(
          row.getOrderId(),
          BookingSlotTimeUtil.startTimeMillis(row.getBookingDate(), row.getBookingTime()));
    }
    return startMillisByOrderId;
  }
}
