package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the auto SP-availability-on cron.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments.
 */
@WorkflowInterface
public interface SpAvailabilityAutoOnWorkflow {

  /** Turns availability on for yesterday's kit-approved SPs. */
  @WorkflowMethod
  void run();
}
