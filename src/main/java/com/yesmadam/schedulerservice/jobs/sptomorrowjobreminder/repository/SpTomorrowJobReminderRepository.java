package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.repository;

import java.sql.Date;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.projections.SpTomorrowJobProjection;


@Repository
public interface SpTomorrowJobReminderRepository extends JpaRepository<Order, Long> {


  @Query(
      value =
          """
    select distinct o.s_p_assign_by_user as spId
    from ysmdm_users.tbl_order o
    where o.booking_date = :bookingDate
      and o.s_p_assign_by_user is not null
      and o.order_status_code not in (:cancelledStatusCodes)
    """,
      nativeQuery = true)
  List<SpTomorrowJobProjection> findSpIdsWithJobOnBookingDate(
      @Param("bookingDate") Date bookingDate,
      @Param("cancelledStatusCodes") List<Integer> cancelledStatusCodes);
}
