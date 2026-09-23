package com.yesmadam.schedulerservice.jobs.couponwhatsapp.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.projections.CouponWhatsappCandidateProjection;


@Repository
public interface CouponWhatsappCandidateRepository extends JpaRepository<Order, Long> {

  @Query(
      value =
          """
    select cc.id                as claimId,
           cc.coupon_id         as couponId,
           cc.order_id          as orderId,
           cc.user_id           as userId,
           u.mobile             as mobile,
           u.name               as customerName,
           cs.brand_name        as brandName,
           cc.code_snapshot     as codeSnapshot,
           cc.expiry_at         as expiryAt
    from ysmdm_users.tbl_coupon_claim cc
    join ysmdm_users.tbl_order o on o.order_id = cc.order_id
    join ysmdm_users.tbl_user u on u.user_id = cc.user_id
    join ysmdm_admin.tbl_coupon_subsection cs on cs.id = cc.coupon_id
    where cc.wa_status = :pendingStatus
      and o.order_status_code in (:statusCodes)
      and o.order_end_time_with_date >= :windowStart
      and o.order_end_time_with_date < :windowEnd
      and u.mobile is not null
    order by cc.id
    """,
      nativeQuery = true)
  List<CouponWhatsappCandidateProjection> findPendingCandidates(
      @Param("pendingStatus") String pendingStatus,
      @Param("statusCodes") List<Integer> statusCodes,
      @Param("windowStart") Timestamp windowStart,
      @Param("windowEnd") Timestamp windowEnd);

  /**
   * Batch-claims the given claims as delivered — one UPDATE for the whole set, not one per row.
   * {@code wa_sent_at} is stamped here, at the moment this job hands the message to SQS. The
   * {@code wa_status = :pendingStatus} guard makes this safe to call again with a claim id that was
   * already claimed (by an earlier attempt of this same activity, or by a previous run): the row
   * simply isn't matched a second time, so re-marking is a no-op rather than an error.
   *
   * @return the number of rows actually updated (may be less than {@code claimIds.size()} if some
   *     were already marked by an earlier attempt).
   */
  @Modifying
  @Query(
      value =
          """
    update ysmdm_users.tbl_coupon_claim
    set wa_status = :deliveredStatus, wa_sent_at = now()
    where id in (:claimIds)
      and wa_status = :pendingStatus
    """,
      nativeQuery = true)
  int markDelivered(
      @Param("claimIds") List<Long> claimIds,
      @Param("deliveredStatus") String deliveredStatus,
      @Param("pendingStatus") String pendingStatus);
}
