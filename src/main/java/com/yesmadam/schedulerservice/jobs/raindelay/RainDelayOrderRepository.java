package com.yesmadam.schedulerservice.jobs.raindelay;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;

/**
 * Reads for the rain-delay job. Native because both queries join {@code ysmdm_users.tbl_order} to
 * {@code ysmdm_admin.tbl_hub} across schemas, which HQL cannot express here.
 *
 * <p>Both key off {@code order_start_time_with_date} rather than parsing {@code booking_time}.
 * Parsing is non-sargable, and {@code STR_TO_DATE(.., '%h:%i %p')} silently returns NULL for the
 * legacy hour-only slot labels ({@code "2 PM - 3 PM"}) that {@code BookingSlotTimeUtil} still has
 * to handle — those rows would vanish from the result with no error.
 *
 * <p>Verify before enabling: {@code SELECT COUNT(*) FROM ysmdm_users.tbl_order WHERE booking_date
 * >= CURDATE() - INTERVAL 7 DAY AND order_start_time_with_date IS NULL}. If non-zero, add the
 * parsed expression back as an OR fallback rather than replacing the indexed predicate.
 */
@Repository
public interface RainDelayOrderRepository extends JpaRepository<Order, Long> {

  @Query(
      value =
          """
    select o.hub_id      as hubId,
           h.hub         as hubName,
           h.lat         as lat,
           h.lng         as lng,
           count(*)      as bookingCount
    from ysmdm_users.tbl_order o
    join ysmdm_admin.tbl_hub h on h.id = o.hub_id
    where o.hub_id in (:hubIds)
      and o.booking_date >= :bookingDateLower
      and o.booking_date <  :bookingDateUpper
      and o.order_start_time_with_date >= :windowStart
      and o.order_start_time_with_date <  :windowEnd
      and o.order_status_code = 3
      and h.lat is not null and trim(h.lat) <> ''
      and h.lng is not null and trim(h.lng) <> ''
    group by o.hub_id, h.hub, h.lat, h.lng
    order by o.hub_id
    """,
      nativeQuery = true)
  List<RainHubCandidateProjection> findRainCheckHubsWithImminentBookings(
      @Param("hubIds") List<Integer> hubIds,
      @Param("bookingDateLower") Date bookingDateLower,
      @Param("bookingDateUpper") Date bookingDateUpper,
      @Param("windowStart") Timestamp windowStart,
      @Param("windowEnd") Timestamp windowEnd);


  @Query(
      value =
          """
    select o.order_id  as orderId,
           o.hub_id    as hubId,
           o.user_id   as userId
    from ysmdm_users.tbl_order o
    where o.hub_id in (:hubIds)
      and o.booking_date >= :bookingDateLower
      and o.booking_date <  :bookingDateUpper
      and o.order_start_time_with_date >= :windowStart
      and o.order_start_time_with_date <  :windowEnd
      and o.order_status_code = 3
      and o.user_id is not null
    order by o.order_id
    """,
      nativeQuery = true)
  List<RainDelayTargetProjection> findNotificationTargets(
      @Param("hubIds") List<Integer> hubIds,
      @Param("bookingDateLower") Date bookingDateLower,
      @Param("bookingDateUpper") Date bookingDateUpper,
      @Param("windowStart") Timestamp windowStart,
      @Param("windowEnd") Timestamp windowEnd);
}
