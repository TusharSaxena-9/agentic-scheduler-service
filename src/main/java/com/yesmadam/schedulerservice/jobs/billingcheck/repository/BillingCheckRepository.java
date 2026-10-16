package com.yesmadam.schedulerservice.jobs.billingcheck.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;
import com.yesmadam.schedulerservice.jobs.billingcheck.projections.BillingCheckOrderProjection;

@Repository
public interface BillingCheckRepository extends JpaRepository<Order, Long> {

  /**
   * One keyset page of orders to message, after {@code afterOrderId}. Native SQL: spans two schemas.
   *
   * <p>Eligibility: status, category, {@code order_end_time_with_date} inside the window, mobile
   * present. No per-customer cap — every qualifying order is messaged.
   *
   * <p>Access path: range scan on {@code idx_order_status_booking_user}, bounded by the
   * {@code booking_date} pre-filter (EXPLAIN ANALYZE on prod: ~25.7k rows scanned, 1.9k matched,
   * ~1.3s warm). Other joins are PK lookups. Read-only, so safe on the replica.
   */
  @Query(
      value =
          """
    select o.order_id                  as orderId,
           u.mobile                    as mobile,
           u.name                      as customerName,
           c.category                  as categoryName,
           coalesce((select group_concat(distinct s.name order by s.name separator ', ')
                     from ysmdm_users.tbl_order_service os
                     join ysmdm_admin.tbl_services s on s.id = os.service_id
                     where os.order_id = o.order_id), '') as serviceNames,
           o.total                     as total,
           o.tip                       as tip,
           o.convenience_fee           as convenienceFee,
           o.transport_charges         as transportCharges,
           o.cod_extra_fee             as codExtraFee,
           o.covidSelfTestAmount       as covidSelfTestAmount,
           o.primetimecharge           as primeTimeCharge,
           la.low_cart_fee             as lowCartFee,
           o.disposable                as disposable,
           o.coupon_discount           as couponDiscount,
           o.walletRedeemAmount        as walletRedeemAmount,
           o.package_discount          as packageDiscount,
           o.myp_package_discount      as mypPackageDiscount,
           o.prevCancellationFee       as prevCancellationFee,
           o.cancellationFeeDiscount   as cancellationFeeDiscount,
           o.el_booking_discount       as elBookingDiscount
    from ysmdm_users.tbl_order o
    join ysmdm_users.tbl_user u on u.user_id = o.user_id
    left join ysmdm_admin.tbl_category c on c.id = o.category_id
    left join ysmdm_users.tbl_order_low_ats_details la on la.order_id = o.order_id
    where o.order_status_code in (:statusCodes)
      and o.category_id in (:categoryIds)
      and o.booking_date >= :bookingDateLower
      and o.booking_date < :bookingDateUpper
      and o.order_end_time_with_date >= :windowStart
      and o.order_end_time_with_date < :windowEnd
      and o.order_id > :afterOrderId
      and u.mobile is not null
    order by o.order_id
    limit :limit
    """,
      nativeQuery = true)
  List<BillingCheckOrderProjection> findEligibleOrders(
      @Param("statusCodes") List<Integer> statusCodes,
      @Param("categoryIds") List<Integer> categoryIds,
      @Param("bookingDateLower") Date bookingDateLower,
      @Param("bookingDateUpper") Date bookingDateUpper,
      @Param("windowStart") Timestamp windowStart,
      @Param("windowEnd") Timestamp windowEnd,
      @Param("afterOrderId") long afterOrderId,
      @Param("limit") int limit);
}
