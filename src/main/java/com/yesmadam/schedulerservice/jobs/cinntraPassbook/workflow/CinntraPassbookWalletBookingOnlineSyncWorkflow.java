package com.yesmadam.schedulerservice.jobs.cinntraPassbook.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the 04:00 wallet-recharge and booking-online incoming payments — tracking's
 * {@code CinntraPassbook.syncWalletAndBookingOnlineToSap}.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments. The window is derived from the scheduled time inside the
 * selection activity.
 */
@WorkflowInterface
public interface CinntraPassbookWalletBookingOnlineSyncWorkflow {

  /** Runs the sync. */
  @WorkflowMethod
  void run();
}
