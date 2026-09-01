package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.activity;

import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.model.OrderCancellationChargeExpiryResult;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface OrderCancellationChargeActivities {

  /**
   * Flags every CREATED/CHARGED cancellation charge whose expiry window has passed as EXPIRED.
   *
   * <p>The cutoff is computed inside this activity, not in workflow code, and returned alongside
   * the count — so a replay's log line reflects the window this run actually used rather than one
   * that has slid forward (CLAUDE.md rule 1).
   *
   * @return the cutoff used and how many rows were updated
   */
  @ActivityMethod
  OrderCancellationChargeExpiryResult expireOverdueCancellationCharges();
}
