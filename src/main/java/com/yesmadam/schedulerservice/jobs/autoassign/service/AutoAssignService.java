package com.yesmadam.schedulerservice.jobs.autoassign.service;

import java.util.List;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignCandidate;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignRunContext;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignThresholds;

public interface AutoAssignService {

  /**
   * Loads everything one auto-assign run needs: today's unassigned candidate orders with their slot
   * starts resolved, and the gates resolved from settings.
   *
   * @param scope which booking day this run targets
   * @return the run context for the workflow to iterate over
   */
  AutoAssignRunContext fetchRunContext(AutoAssignScope scope);

  /**
   * Decides and acts on one chunk of candidates: auto-assign the ones still inside the gates, and
   * collect the rest for Auto Job Distribution.
   *
   * @param chunk the candidate orders in this chunk
   * @param thresholds the gates for this run
   * @param scope which booking day this run targets — decides whether the D-1 bypass applies
   * @param progress reports orders finished, so a chunk that wedges mid-way is detected by its
   *     heartbeat timeout rather than only when the whole attempt times out
   * @return per-outcome counts, the distribution candidates, and per-query diagnostics
   */
  AutoAssignChunkResult processChunk(
      List<AutoAssignCandidate> chunk,
      AutoAssignThresholds thresholds,
      AutoAssignScope scope,
      ProgressReporter progress);

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
  DistributionTriggerResult dispatchDistributionBatch(List<Long> orderIds);
}
