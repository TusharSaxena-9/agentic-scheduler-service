package com.yesmadam.schedulerservice.jobs.sebaltagging.repository;

import java.time.LocalDate;
import java.util.List;

import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.QualifyingFirstOrderProjection;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.SubsequentOrderProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.yesmadam.persistence.Order;

/**
 * Native queries against the order tables that back SEBAL sub-category tagging. Kept separate
 * from the current-state/ledger JPA repositories below since these read raw order data
 * ({@code ysmdm_users.tbl_order}, {@code tbl_order_service}) rather than the tagging tables
 * themselves.
 *
 * <p>Two queries — the qualifying first-order cohort (with its dominant sub-category resolved
 * inline) and the subsequent orders for the rebook check. Both are scoped to the caller's chunk
 * window, so neither scans the full order history.
 *
 * <p>The general convention in this service ({@code RoseOrderServiceImpl}) is several narrow DAO
 * calls merged in Java. Sub-category resolution is the deliberate exception: it's a per-order
 * attribute, and keying a follow-up query on the returned order ids meant an {@code IN (...)} list
 * large enough to cost the optimizer its index — see
 * {@link #findQualifyingFirstOrders}. Anything that would feed tens of thousands of ids back into
 * a second query belongs in the first one.
 */
@org.springframework.stereotype.Repository
public interface SebalTaggingCohortRepository extends Repository<Order, Long> {

  /**
   * Every user's absolute first-ever completed order ({@code order_status_code in (5, 6)}) whose
   * {@code booking_date} falls in {@code [startDate, endDate]}, together with that order's
   * dominant sub-category — the sub-category of its highest-priced service line item, the same
   * tie-break PRD Module 2.3 uses for multi-sub-category orders.
   *
   * <p>"First-ever" is decided by a {@code NOT EXISTS} check against that user's own
   * completed-order history (not scoped to the window) — the only unbounded-lookback part of this
   * query, but it's a covering index lookup per candidate row, not a full-table scan. The
   * {@code (booking_date, order_id)} tie-break handles two completed orders landing on the exact
   * same {@code booking_date} for one user.
   *
   * <p>The {@code s_p_assign_by_user is not null} filter deliberately sits <em>outside</em> the
   * antijoin: "first-ever" has to be judged against all completed orders, including ones with no
   * SP assigned. Fold that predicate into the {@code NOT EXISTS} and a user whose genuine first
   * order had no SP would have their second order wrongly treated as their first.
   *
   * <p>The sub-category resolution is joined here rather than done as a follow-up call keyed on
   * the returned order ids. That earlier shape passed up to ~50k ids in an {@code IN (...)} list,
   * which past roughly 20k ids pushed the optimizer off the {@code tbl_order_service.order_id}
   * index and onto a full scan — a flat ~104s per chunk regardless of chunk size, 85% of a FULL
   * run's total time. Driving the join from this query's own (already narrow) result set keeps it
   * a nested-loop index lookup: measured 3.4s against 275s for the same chunk.
   *
   * <p>Orders with no resolvable service/sub-category row are dropped by the inner joins, exactly
   * as the previous two-step version dropped them after the fact.
   */
  //// this can be fixed with backfiring and registering first orders as rose orders.
  @Query(
      value =
          """
    with fo as (
        select o.order_id,
               o.user_id,
               o.s_p_assign_by_user as sp_id,
               o.booking_date
        from ysmdm_users.tbl_order o
        where o.order_status_code in (5, 6)
          and o.booking_date between :startDate and :endDate
          and o.s_p_assign_by_user is not null
          and not exists (
            select 1
            from ysmdm_users.tbl_order o2
            where o2.user_id = o.user_id
              and o2.order_status_code in (5, 6)
              and (o2.booking_date < o.booking_date
                   or (o2.booking_date = o.booking_date and o2.order_id < o.order_id))
          )
    ),
    ranked as (
        select fo.order_id,
               fo.user_id,
               fo.sp_id,
               fo.booking_date,
               sc.id as sub_category_id,
               row_number() over (
                 partition by fo.order_id
                 order by os.service_charge desc, sc.id asc
               ) as rn
        from fo
        join ysmdm_users.tbl_order_service os on os.order_id = fo.order_id
        join ysmdm_admin.tbl_services s on s.id = os.service_id
        join ysmdm_admin.tbl_subcategory sc on sc.id = s.sub_cat_id
    )
    select order_id        as orderId,
           user_id         as userId,
           sp_id           as spId,
           booking_date    as bookingDate,
           sub_category_id as subCategoryId
    from ranked
    where rn = 1
    """,
      nativeQuery = true)
  List<QualifyingFirstOrderProjection> findQualifyingFirstOrders(
      @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

  /**
   * Every completed order for the given users with {@code booking_date} between
   * {@code lookaheadStart} and {@code lookaheadEnd} — the raw material for the 90-day rebook
   * check. Callers determine, per qualifying first-order, whether any row here for that user has
   * a later {@code booking_date} within 90 days (done in Java since the 90-day deadline is
   * per-order, not uniform across the batch).
   */
  @Query(
      value =
          """
    select o.user_id      as userId,
           o.order_id      as orderId,
           o.booking_date  as bookingDate
    from ysmdm_users.tbl_order o
    where o.user_id in (:userIds)
      and o.order_status_code in (5, 6)
      and o.booking_date between :lookaheadStart and :lookaheadEnd
    """,
      nativeQuery = true)
  List<SubsequentOrderProjection> findCompletedOrdersForRebookCheck(
      @Param("userIds") List<Long> userIds,
      @Param("lookaheadStart") LocalDate lookaheadStart,
      @Param("lookaheadEnd") LocalDate lookaheadEnd);
}
