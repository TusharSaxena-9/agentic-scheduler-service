package com.yesmadam.schedulerservice.jobs.billingcheck.activity;

import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckChunk;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckSendResult;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface BillingCheckActivities {

  /**
   * Reads one page of the window's eligible orders, strictly after {@code afterOrderId}. A pure
   * read — safe to retry.
   *
   * @param nominalFireTimeMillis the run's fixed reference time; the window is derived from it, so a
   *     retry or a later page always covers the same 24 hours
   */
  @ActivityMethod
  BillingCheckChunk fetchChunk(long nominalFireTimeMillis, long afterOrderId);

  /** Sends the WhatsApp for every order in the page. NOT safe to retry — the workflow runs it once. */
  @ActivityMethod
  BillingCheckSendResult sendChunk(BillingCheckChunk chunk);
}
