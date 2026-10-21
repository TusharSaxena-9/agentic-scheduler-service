package com.yesmadam.schedulerservice.jobs.billingcheck.projections;

/** One eligible order. Bill components are raw; {@code BillingAmountCalculator} sums them. */
public interface BillingCheckOrderProjection {

  Long getOrderId();

  Long getMobile();

  String getCustomerName();

  String getCategoryName();

  /** Comma-separated billed service names; empty if none. */
  String getServiceNames();

  Integer getTotal();

  Integer getTip();

  Double getConvenienceFee();

  Integer getTransportCharges();

  Integer getCodExtraFee();

  Integer getCovidSelfTestAmount();

  Integer getPrimeTimeCharge();

  Integer getLowCartFee();

  Integer getDisposable();

  Integer getCouponDiscount();

  Integer getWalletRedeemAmount();

  Integer getPackageDiscount();

  Integer getMypPackageDiscount();

  Integer getPrevCancellationFee();

  Integer getCancellationFeeDiscount();

  Integer getElBookingDiscount();
}
