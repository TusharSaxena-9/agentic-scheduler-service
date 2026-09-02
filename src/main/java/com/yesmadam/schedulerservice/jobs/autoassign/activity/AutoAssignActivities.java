package com.yesmadam.schedulerservice.jobs.autoassign.activity;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignCandidate;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignRunContext;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignThresholds;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for the same-day auto-assign run.
 *
 * <p>The run is split into one read-only context fetch, one activity call per chunk of candidate
 * orders, and one call per distribution batch. Each chunk's completion is recorded in workflow
 * history, so a failure resumes from the failed chunk instead of re-running the whole day's orders.
 */
@ActivityInterface
public interface AutoAssignActivities {

  /**
   * Loads today's unassigned candidate orders and the gates for this run.
   *
   * @param scope which booking day this run targets
   * @return the run context the workflow iterates over
   */
  @ActivityMethod
  AutoAssignRunContext fetchAutoAssignRunContext(AutoAssignScope scope);

  /**
   * Auto-assigns one chunk of candidate orders and collects the ones that must go to distribution
   * instead.
   *
   * <p>Per-order failures are absorbed and counted rather than thrown, matching the cron's
   * per-order {@code try/catch}. That also makes a chunk retry safe: the activity effectively never
   * fails part-way through, so Temporal cannot re-fire assign calls for orders an earlier attempt
   * already handled.
   *
   * @param chunk the candidate orders in this chunk
   * @param thresholds the gates for this run
   * @param scope which booking day this run targets — decides whether the D-1 bypass applies
   * @return per-outcome counts, the chunk's distribution candidates, and per-query diagnostics
   */
  @ActivityMethod
  AutoAssignChunkResult processAutoAssignChunk(
      List<AutoAssignCandidate> chunk, AutoAssignThresholds thresholds, AutoAssignScope scope);

  /**
   * Hands one batch of order ids to the Auto Job Distribution engine, which now runs in this
   * service rather than behind an HTTP call to cron.
   *
   * <p>The engine filters, enqueues, and opens the fire gate once — so a batch of 200 results in at
   * most one order actually being distributed. A result with {@code fired = 0} is the ordinary
   * case.
   *
   * @param orderIds the orders to distribute
   * @return what the batch did: received, eligible, enqueued, inserted, fired
   */
  @ActivityMethod
  DistributionTriggerResult dispatchDistributionBatch(List<Long> orderIds);
}
