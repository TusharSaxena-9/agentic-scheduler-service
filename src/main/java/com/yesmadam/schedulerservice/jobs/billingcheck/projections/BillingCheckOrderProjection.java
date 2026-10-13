package com.yesmadam.schedulerservice.jobs.billingcheck.projections;

/**
 * One eligible order with everything the message needs. The bill components are returned raw (not
 * summed in SQL) so the amount rule lives in one tested Java method, {@code BillingAmountCalculator},
 * mirroring app-server's {@code OrderUtilities.calculateOrderTotal}.
 */
public interface BillingCheckOrderProjection {

  Long getOrderId();

  Long getMobile();

  String getCustomerName();

  String getCategoryName();

  /** Comma-separated billed service names; empty string when the order has none. */
  String getServiceNames();

  Integer getTotal();

  Integer getTip();

  Double getConvenienceFee();

  Integer getTransportCharges();

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
