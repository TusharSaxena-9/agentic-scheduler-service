package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the elite-fee-mismatch-to-SAP sync cron. Runs once daily at 7:40 AM —
 * mirrors tracking's {@code EliteMismatch.getEliteMismatch}.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar
 * starts every job's workflow with no arguments. The "yesterday" window is derived inside the
 * fetch activity.
 */
@WorkflowInterface
public interface EliteMismatchSyncWorkflow {

  /** Runs the elite-fee-mismatch sync. */
  @WorkflowMethod
  void run();
}
