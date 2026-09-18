package com.yesmadam.schedulerservice.jobs.paymentrefund.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the payment-refund order-creation reconciliation cron, ported from {@code
 * shortlink}'s {@code PaymentRefundComponent.validateOrderCreationOnPayment}.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments.
 */
@WorkflowInterface
public interface PaymentRefundWorkflow {

  /** Re-verifies every pending checksum row in the trailing window and resolves each one. */
  @WorkflowMethod
  void run();
}
