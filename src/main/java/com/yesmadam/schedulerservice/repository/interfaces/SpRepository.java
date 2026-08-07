package com.yesmadam.schedulerservice.repository.interfaces;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SPRegistration;
import com.yesmadam.schedulerservice.jobs.roseorder.model.SpOverallRatingProjection;

@Repository
public interface SpRepository extends JpaRepository<SPRegistration, Integer> {

  @Query("select s.id from SPRegistration s where s.status = :status")
  List<Integer> findIdsByStatus(@Param("status") Boolean status);

  @Query(
      "select o.s_p_assign_by_user , count(o) from Order o where o.s_p_assign_by_user in (:spIds) and o.order_status_code in (:completedOrderCodes) group by o.s_p_assign_by_user ")
  List<Object[]> countCompletedOrdersCountForSpIDs(
      @Param("spIds") List<Integer> spIds,
      @Param("completedOrderCodes") List<Integer> completedOrderCodes);

  @Query(
      value =
          """
    select t.sp_id        as spId,
           avg(t.rating)  as avgRating,
           count(*)       as ratedInWindow
    from (
        select sp_id, rating,
               row_number() over (partition by sp_id order by created_at desc) rn
        from ysmdm_users.tbl_order_feedback
        where sp_id in (:spIds)
    ) t
    where t.rn <= :maxRatingCount
    group by t.sp_id
    """,
      nativeQuery = true)
  List<SpOverallRatingProjection> spAvgRatingForLastOrders(
      @Param("spIds") List<Integer> spIds, @Param("maxRatingCount") int maxRatingCount);

  @Query(
      value =
          """
    select oh.sp_id, count(*)
    from ysmdm_users.tbl_order o
    join ysmdm_users.tbl_order_history oh on oh.order_id = o.order_id
    where o.booking_date between current_date - interval :days day and current_date
      and oh.sp_id is not null
      and oh.message like '%assign%'
      and o.order_status_code in (5,6)
      and oh.sp_id in (:spIds)
    group by oh.sp_id
    """,
      nativeQuery = true)
  List<Object[]> jobAssignedCountPerSP(
      @Param("spIds") List<Integer> spIds, @Param("days") int days);

  @Query(
      value =
          """
    select s_p_assign_by_user as spId,
           count(*)           as completedCount
    from ysmdm_users.tbl_order
    where booking_date between current_date - interval :days day and current_date
      and s_p_assign_by_user in (:spIds)
      and order_status_code in (:completedOrderCodes)
    group by s_p_assign_by_user
    """,
      nativeQuery = true)
  List<Object[]> jobCompletedCountPerSP(
      @Param("spIds") List<Integer> spIds,
      @Param("days") int days,
      @Param("completedOrderCodes") List<Integer> completedOrderCodes);

  @Query(
      value =
          """
    select n.created_by as spId, count(distinct n.order_id) as rejectedCount
    from ysmdm_users.tbl_order_notification n
    where n.notification = 'ORDER_REJECTED'
      and n.created_by in (:spIds)
      and n.created_at >= current_date - interval :days day
    group by n.created_by
    """,
      nativeQuery = true)
  List<Object[]> jobsRejectedCountPerSP(
      @Param("spIds") List<Integer> spIds, @Param("days") int days);

  @Query(
      value =
          """
    select c.defaulter_id as spId, count(distinct c.order_id) as defaulterCount
    from ysmdm_users.tbl_cancel_ops c
    where c.defaulter_id in (:spIds)
      and c.order_id is not null
      and c.cancelling_datetime >= current_date - interval :days day
    group by c.defaulter_id
    """,
      nativeQuery = true)
  List<Object[]> jobsMarkedDefaulterCountPerSP(
      @Param("spIds") List<String> spIds, @Param("days") int days);

  @Query(
      value =
          """
    select n.created_by as spId, count(n.id) as cancelButSuccessCount
    from ysmdm_users.tbl_order o
    join ysmdm_users.tbl_order_notification n on o.order_id = n.order_id
    where o.order_status in ('ORDER COMPLETED', 'ORDER FEEDBACK DONE')
      and n.notification = 'CUSTOMER_WANT_TO_CANCEL'
      and n.created_by in (:spIds)
      and o.spid <> n.created_by
      and n.created_at >= current_date - interval :days day
    group by n.created_by
    """,
      nativeQuery = true)
  List<Object[]> jobsCancelButJobSuccessCountPerSP(
      @Param("spIds") List<Integer> spIds, @Param("days") int days);

  @Query(
      value =
          """
    select sp_id as spId, count(*) as impactedBySPLeaveCount
    from ysmdm_admin.tbl_sp_escalation
    where main_concern_code = 9
      and sp_id in (:spIds)
      and createdAt >= current_date - interval :days day
    group by sp_id
    """,
      nativeQuery = true)
  List<Object[]> jobsImpactedBySPLeaveCountPerSP(
      @Param("spIds") List<Integer> spIds, @Param("days") int days);
}
