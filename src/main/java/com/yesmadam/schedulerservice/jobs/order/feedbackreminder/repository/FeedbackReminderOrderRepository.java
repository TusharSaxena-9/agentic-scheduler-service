package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.projections.PendingFeedbackOrderProjection;

@Repository
public interface FeedbackReminderOrderRepository extends JpaRepository<Order, Long> {

  @Query(
      value =
          """
    select o.order_id                                                   as orderId,
           u.mobile                                                     as mobile,
           u.name                                                       as customerName,
           o.order_start_time_with_date                                 as orderStartTime,
           coalesce(group_concat(distinct s.name order by s.name separator ', '), '') as serviceNames
    from ysmdm_users.tbl_order o
    join ysmdm_users.tbl_user u on u.user_id = o.user_id
    left join ysmdm_users.tbl_order_service os on os.order_id = o.order_id
    left join ysmdm_admin.tbl_services s on s.id = os.service_id
    where o.order_status_code = :statusCode
      and o.booking_date >= :bookingDateLower
      and o.booking_date < :bookingDateUpper
      and o.order_end_time_with_date >= :windowStart
      and o.order_end_time_with_date < :windowEnd
      and u.mobile is not null
    group by o.order_id, u.mobile, u.name, o.order_start_time_with_date
    order by o.order_id
    """,
      nativeQuery = true)
  List<PendingFeedbackOrderProjection> findCompletedOrdersPendingFeedback(
      @Param("statusCode") Integer statusCode,
      @Param("bookingDateLower") Date bookingDateLower,
      @Param("bookingDateUpper") Date bookingDateUpper,
      @Param("windowStart") Timestamp windowStart,
      @Param("windowEnd") Timestamp windowEnd);
}
