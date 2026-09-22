package com.yesmadam.schedulerservice.jobs.couponwhatsapp.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the coupon-WhatsApp reminder cron. Enqueues, onto SQS, one message per
 * claimed brand coupon whose booking completed yesterday and whose WhatsApp message hasn't been
 * sent yet — actual sending happens downstream in cxconsumers.
 *
 * <p>No-arg by design so it fits the shared schedule registrar, which starts every job's workflow
 * with no arguments.
 */
@WorkflowInterface
public interface CouponWhatsappReminderWorkflow {

  /** Executes one coupon-WhatsApp reminder enqueue run for yesterday's completed orders. */
  @WorkflowMethod
  void run();
}
