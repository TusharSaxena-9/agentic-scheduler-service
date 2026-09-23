package com.yesmadam.schedulerservice.jobs.couponwhatsapp.projections;

import java.sql.Timestamp;

/** One row of the native join in {@link com.yesmadam.schedulerservice.jobs.couponwhatsapp.repository.CouponWhatsappCandidateRepository}. */
public interface CouponWhatsappCandidateProjection {

  Long getClaimId();

  Integer getCouponId();

  Long getOrderId();

  Long getUserId();

  Long getMobile();

  String getCustomerName();

  String getBrandName();

  String getCodeSnapshot();

  Timestamp getExpiryAt();
}
