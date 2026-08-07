package com.yesmadam.schedulerservice.jobs.roseorder.service;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderChunkResult;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderEligibilityThresholds;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderRunContext;

public interface RoseOrderService {

  /**
   * Loads everything one eligibility run needs: all active SP ids and the thresholds resolved from
   * settings.
   *
   * @return the run context for the workflow to iterate over
   */
  RoseOrderRunContext fetchRunContext();

  /**
   * Evaluates one chunk of SPs against the given thresholds and persists the current-state upsert
   * plus audit ledger rows atomically.
   *
   * @param spIds the SP ids in this chunk
   * @param thresholds the eligibility gates for this run
   * @param runId the workflow run id to stamp onto each ledger row for this run
   * @return evaluated/eligible counts for run totals, plus per-query timing/row-count diagnostics
   */
  RoseOrderChunkResult processChunk(
      List<Integer> spIds, RoseOrderEligibilityThresholds thresholds, String runId);
}
