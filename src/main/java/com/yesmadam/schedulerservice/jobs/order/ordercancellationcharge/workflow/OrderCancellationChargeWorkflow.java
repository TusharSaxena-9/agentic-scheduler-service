package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the order cancellation charge expiry cron.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments.
 */
@WorkflowInterface
public interface OrderCancellationChargeWorkflow {

  /** Expires every overdue CREATED/CHARGED cancellation charge. */
  @WorkflowMethod
  void run();
}
