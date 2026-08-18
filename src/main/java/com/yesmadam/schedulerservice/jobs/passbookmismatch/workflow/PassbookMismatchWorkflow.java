package com.yesmadam.schedulerservice.jobs.passbookmismatch.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the SP passbook mismatch audit cron.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments. The window is derived inside the run.
 */
@WorkflowInterface
public interface PassbookMismatchWorkflow {

  /** Audits one window and mails anything it finds. */
  @WorkflowMethod
  void run();
}
