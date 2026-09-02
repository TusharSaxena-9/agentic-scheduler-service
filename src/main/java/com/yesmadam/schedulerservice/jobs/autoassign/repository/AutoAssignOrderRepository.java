package com.yesmadam.schedulerservice.jobs.autoassign.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;
import com.yesmadam.schedulerservice.jobs.autoassign.projections.UnassignedOrderProjection;

/**
 * Reads backing the auto-assign run. Native queries throughout: {@code order_start_time_with_date}
 * and {@code unassignWithRemoveReassigning} are real {@code tbl_order} columns that the shared
 * {@code com.yesmadam.persistence.Order} entity does not map, and {@code tbl_job_distribution} has
 * no entity in this service at all.
 *
 * <p>{@code unassignWithRemoveReassigning} is spelled camelCase on purpose — it is the literal
 * column name. No {@code @Column} override exists for it anywhere in the platform, and every
 * service runs {@code PhysicalNamingStrategyStandardImpl}, which leaves the property name
 * untouched.
 */
@Repository
public interface AutoAssignOrderRepository extends JpaRepository<Order, Long> {

  /**
   * Unassigned orders on one booking date that still have enough runway to be worth auto-assigning.
   *
   * <p>Ported from cron's {@code OrderDaoImpl#getOrdersByBookingDateWithNonAssigning}: no SP
   * assigned, not deliberately pulled out of the pool by ops via {@code
   * unassignWithRemoveReassigning}, and starting at least {@code minStartTime} from now.
   *
   * <p><b>Access path:</b> {@code idx_order_booking_sp_servicetype_status (booking_date,
   * s_p_assign_by_user, is_servicetype, order_status_code)}. Verified on UAT: {@code type=ref,
   * key=idx_order_booking_sp_servicetype_status, Using index condition; Using where}. The leading
   * two columns match this query's {@code booking_date = ?} and {@code s_p_assign_by_user IS NULL}
   * exactly, which is why it is a ref lookup rather than a scan of the day.
   *
   * <p>{@code booking_date} is a {@code datetime}, not a {@code date}, so the equality only matches
   * rows stored at midnight. Every row sampled on UAT is, and this mirrors cron's own Criteria
   * equality, so it is not a regression — but a row written with a time component would silently
   * vanish from this result.
   *
   * @param statusCodes order statuses in scope (new request / assigned-to-SP)
   * @param bookingDate the booking date to scan
   * @param minStartTime earliest slot start still considered actionable
   */
  @Query(
      value =
          """
    select o.order_id     as orderId,
           cast(o.booking_date as date) as bookingDate,
           o.booking_time as bookingTime
    from ysmdm_users.tbl_order o
    where o.order_status_code in (:statusCodes)
      and o.booking_date = :bookingDate
      and o.order_start_time_with_date >= :minStartTime
      and o.s_p_assign_by_user is null
      and (o.unassignWithRemoveReassigning is null or o.unassignWithRemoveReassigning = false)
    """,
      nativeQuery = true)
  List<UnassignedOrderProjection> findUnassignedOrdersForBookingDate(
      @Param("statusCodes") List<Integer> statusCodes,
      @Param("bookingDate") Date bookingDate,
      @Param("minStartTime") Timestamp minStartTime);

  /**
   * Unassigned orders on one booking date, with <b>no lead-time floor</b> — the D-1 variant.
   *
   * <p>Identical to {@link #findUnassignedOrdersForBookingDate} except for the missing {@code
   * order_start_time_with_date} predicate, which mirrors cron's {@code
   * getOrdersByBookingDateWithNonAssigning}: it wraps that restriction in {@code if
   * (bookingLocalDate.isEqual(today))} and therefore never applies it to tomorrow's bookings.
   *
   * <p>Kept as a separate method rather than folding a {@code (:minStartTime IS NULL OR ...)} guard
   * into the shared query, so each of the two keeps a simple, predictable plan on a table this
   * large.
   *
   * <p><b>Access path:</b> same index as {@link #findUnassignedOrdersForBookingDate} — {@code
   * idx_order_booking_sp_servicetype_status}, leading on {@code booking_date} and {@code
   * s_p_assign_by_user}. Dropping the {@code order_start_time_with_date} predicate does not change
   * the access path, since that column is not in this index; it only widens the matched set.
   *
   * @param statusCodes order statuses in scope (new request / assigned-to-SP)
   * @param bookingDate the booking date to scan
   */
  @Query(
      value =
          """
    select o.order_id     as orderId,
           cast(o.booking_date as date) as bookingDate,
           o.booking_time as bookingTime
    from ysmdm_users.tbl_order o
    where o.order_status_code in (:statusCodes)
      and o.booking_date = :bookingDate
      and o.s_p_assign_by_user is null
      and (o.unassignWithRemoveReassigning is null or o.unassignWithRemoveReassigning = false)
    """,
      nativeQuery = true)
  List<UnassignedOrderProjection> findUnassignedOrdersForBookingDateWithoutLeadTime(
      @Param("statusCodes") List<Integer> statusCodes, @Param("bookingDate") Date bookingDate);

  /**
   * Reassign attempts per order, counted off {@code tbl_order_history}. Only the three assign types
   * that represent a genuine reassignment are counted — the same three the cron counts.
   *
   * <p>Orders with no history rows are simply absent from the result; callers default them to zero.
   *
   * <p><b>Returns {@code [orderId, count]} pairs, not a typed projection.</b> MySQL hands back
   * {@code BigInteger} for a {@code BIGINT UNSIGNED} key and {@code BigDecimal} for a {@code
   * SUM()}, and an interface projection would have to rely on Spring Data's conversion service to
   * narrow both to {@code Long}/{@code Integer} — a dependency that is invisible at compile time
   * and only fails on the first real row. Widening to {@code Number} and converting explicitly in
   * the caller removes the guess. Same pattern as {@code
   * DistributionOrderRepository#findReassignCounts}.
   */
  @Query(
      value =
          """
    select oh.order_id as orderId,
           sum(case when oh.assign_type in ('Cron Reassign','Normal Reassign','Forcefully Reassign')
                    then 1 else 0 end) as reassignCount
    from ysmdm_users.tbl_order_history oh
    where oh.order_id in (:orderIds)
    group by oh.order_id
    """,
      nativeQuery = true)
  List<Object[]> findReassignCounts(@Param("orderIds") List<Long> orderIds);

  /**
   * Live slot fields for orders already in flight, re-read per chunk.
   *
   * <p>Exists because of Temporal's chunked execution: the run context resolves each order's slot
   * start once, but chunks execute minutes apart and an order can be rescheduled in between. The
   * gate that decides auto-assign versus distribution turns on minutes-to-start, so it must see the
   * slot as it is now, not as it was when the workflow started. The cron had no equivalent need —
   * it evaluated every order within milliseconds of its own fetch.
   *
   * <p><b>Access path:</b> {@code PRIMARY} on {@code tbl_order} — {@code order_id IN (...)} is a
   * primary-key range, bounded by the chunk size (25), so this is 25 point lookups per chunk.
   */
  @Query(
      value =
          """
    select o.order_id     as orderId,
           cast(o.booking_date as date) as bookingDate,
           o.booking_time as bookingTime
    from ysmdm_users.tbl_order o
    where o.order_id in (:orderIds)
    """,
      nativeQuery = true)
  List<UnassignedOrderProjection> findSlotTimes(@Param("orderIds") List<Long> orderIds);

  /**
   * Orders whose assigned SP rejected the job after {@code cutoff} — the D-1-only bypass signal.
   *
   * <p>Matched on message text because neither the original assignment nor the rejection writes an
   * {@code assign_type}. The single {@code LIKE} covers both live message variants empapi emits
   * ("...due to job canelled by - ... due to job rejection by sp" and "...due to job rejection by
   * sp"), so do not tighten it to an exact match.
   *
   * <p><b>Access path:</b> {@code idx_order_id} on {@code tbl_order_history}, verified on UAT as
   * {@code type=range, key=idx_order_id, rows=7, Using index condition}. The leading wildcard in
   * the {@code LIKE} cannot use an index, but {@code order_id IN (...)} is applied first, so the
   * {@code LIKE} only ever scans the matched orders' own history rows — 7 examined, not a table
   * scan.
   *
   * <p>Returns {@code Number} for the same reason as {@link #findAcceptedDistributionOrderIds}: a
   * single-column native query has no projection proxy to narrow the driver's type.
   */
  @Query(
      value =
          """
    select distinct oh.order_id
    from ysmdm_users.tbl_order_history oh
    where oh.order_id in (:orderIds)
      and oh.log_at >= :cutoff
      and oh.message like '%due to job rejection by sp%'
    """,
      nativeQuery = true)
  List<Number> findSpRejectedAfterCutoff(
      @Param("orderIds") List<Long> orderIds, @Param("cutoff") Timestamp cutoff);

  /**
   * Orders that already had a distribution round accepted by an SP and are back in the unassigned
   * pool — meaning the SP accepted and then cancelled. These skip auto-assign entirely and go
   * straight back to distribution.
   *
   * <p>Returns {@code Number}, not {@code Long}: a single-column native query hands back whatever
   * the JDBC driver produces for the column with no projection proxy to convert it, and MySQL
   * yields {@code BigInteger} for {@code BIGINT UNSIGNED}. The caller narrows it.
   */
  @Query(
      value =
          """
    select distinct jd.order_id
    from ysmdm_users.tbl_job_distribution jd
    where jd.order_id in (:orderIds)
      and jd.status = 'ACCEPTED'
    """,
      nativeQuery = true)
  List<Number> findAcceptedDistributionOrderIds(@Param("orderIds") List<Long> orderIds);
}
