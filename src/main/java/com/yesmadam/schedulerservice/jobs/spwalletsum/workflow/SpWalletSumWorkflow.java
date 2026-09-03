package com.yesmadam.schedulerservice.jobs.spwalletsum.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the daily SP wallet sum snapshot.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments.
 */
@WorkflowInterface
public interface SpWalletSumWorkflow {

  /** Sums every SP's main and saving wallet balance and snapshots the totals. */
  @WorkflowMethod
  void run();
}
