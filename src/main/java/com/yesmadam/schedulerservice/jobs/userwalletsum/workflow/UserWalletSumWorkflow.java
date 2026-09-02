package com.yesmadam.schedulerservice.jobs.userwalletsum.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the daily user wallet sum snapshot.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments.
 */
@WorkflowInterface
public interface UserWalletSumWorkflow {

  /** Sums every user's wallet balance and snapshots the total. */
  @WorkflowMethod
  void run();
}
