package com.yesmadam.schedulerservice.jobs.autoassign.enums;

/**
 * What the run decided to do with one candidate order.
 *
 * <p>Replaces the cron's per-order debug rows in {@code ysmdm_admin.abc_sunil_logs}: the counts
 * ride back to the workflow in {@link
 * com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult} and land in Temporal
 * history, so a run's shape is visible in the Temporal UI without a scratch table.
 */
public enum AutoAssignOutcome {

  /** Auto-assign was called for the order and the API accepted the request. */
  ASSIGN_TRIGGERED,

  /** Auto-assign was called but the API errored or the call itself failed. */
  ASSIGN_FAILED,

  /**
   * Order was not auto-assigned — reassign attempts exhausted, an SP already accepted then
   * cancelled it, or it starts too soon — and was handed to Auto Job Distribution instead.
   */
  QUEUED_FOR_DISTRIBUTION
}
