package com.yesmadam.schedulerservice.jobs.couponwhatsapp.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for the coupon-WhatsApp enqueue job: one activity that computes
 * yesterday's completed-order window, fetches every claim still pending its WhatsApp message, and
 * publishes each to SQS.
 */
@ActivityInterface
public interface CouponWhatsappReminderActivities {

  /** Fetches every candidate claim for yesterday's completed orders and publishes each to SQS.
   *  Returns the number of candidates published. */
  @ActivityMethod
  int enqueuePendingCouponWhatsapp();
}
