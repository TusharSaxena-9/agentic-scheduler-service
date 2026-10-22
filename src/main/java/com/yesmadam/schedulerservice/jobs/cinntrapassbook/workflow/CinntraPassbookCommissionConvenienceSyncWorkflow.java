package com.yesmadam.schedulerservice.jobs.cinntrapassbook.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the 01:00 commission and convenience-fee invoices — tracking's {@code
 * CinntraPassbook.syncCommissionAndConvenienceToSap}.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments. The window is derived from the scheduled time inside the
 * selection activity.
 */
@WorkflowInterface
public interface CinntraPassbookCommissionConvenienceSyncWorkflow {

  /** Runs the sync. */
  @WorkflowMethod
  void run();
}
