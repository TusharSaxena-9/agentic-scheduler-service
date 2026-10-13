package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import com.yesmadam.schedulerservice.jobs.billingcheck.projections.BillingCheckOrderProjection;

/**
 * The final bill shown to the customer. A line-for-line port of app-server's {@code
 * OrderUtilities.calculateOrderTotal} — if that rule changes, this must change with it, or the
 * message quotes an amount the customer never paid.
 */
final class BillingAmountCalculator {

  private BillingAmountCalculator() {}

  static long finalAmount(BillingCheckOrderProjection p) {
    long charges =
        n(p.getTip())
            + (p.getConvenienceFee() != null ? p.getConvenienceFee().longValue() : 0L)
            + n(p.getTransportCharges())
            + n(p.getCovidSelfTestAmount())
            + n(p.getPrimeTimeCharge())
            + n(p.getLowCartFee())
            + n(p.getDisposable());
    long discounts =
        n(p.getCouponDiscount())
            + n(p.getWalletRedeemAmount())
            + n(p.getPackageDiscount())
            + n(p.getMypPackageDiscount());
    return n(p.getTotal())
        + charges
        - discounts
        + n(p.getPrevCancellationFee())
        - n(p.getCancellationFeeDiscount())
        - n(p.getElBookingDiscount());
  }

  private static long n(Integer value) {
    return value != null ? value : 0L;
  }
}
