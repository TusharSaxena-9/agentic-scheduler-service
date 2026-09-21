package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the 20:00 kit-approved notification cron.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments.
 */
@WorkflowInterface
public interface SpKitApprovedNotifyWorkflow {

  /** Pushes the heads-up to SPs whose first kit was approved yesterday. */
  @WorkflowMethod
  void run();
}
