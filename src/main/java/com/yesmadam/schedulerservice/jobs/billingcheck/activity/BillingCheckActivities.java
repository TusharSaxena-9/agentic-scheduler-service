package com.yesmadam.schedulerservice.jobs.billingcheck.activity;

import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckChunk;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckSendResult;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface BillingCheckActivities {

  /** Reads one page of eligible orders after {@code afterOrderId}. A pure read, safe to retry. */
  @ActivityMethod
  BillingCheckChunk fetchChunk(long nominalFireTimeMillis, long afterOrderId);

  /** Sends the WhatsApp for every order in the page. Not safe to retry. */
  @ActivityMethod
  BillingCheckSendResult sendChunk(BillingCheckChunk chunk);
}
