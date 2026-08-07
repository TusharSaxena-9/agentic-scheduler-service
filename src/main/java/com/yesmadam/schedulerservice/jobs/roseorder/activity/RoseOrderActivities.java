package com.yesmadam.schedulerservice.jobs.roseorder.activity;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderChunkResult;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderEligibilityThresholds;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderRunContext;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for the rose order eligibility run.
 *
 * <p>The run is split into one context fetch plus one activity call per chunk, so each chunk is an
 * independently retried step whose completion is recorded in workflow history — a failure resumes
 * from the failed chunk instead of re-running the whole pool.
 */
@ActivityInterface
public interface RoseOrderActivities {

  /**
   * Loads the SP ids and thresholds for one eligibility run.
   *
   * @return the run context the workflow iterates over
   */
  @ActivityMethod
  RoseOrderRunContext fetchRoseOrderRunContext();

  /**
   * Evaluates and persists one chunk of SPs. Atomic: the chunk's current-state upsert and ledger
   * rows commit in a single transaction, so a retry of this activity never leaves partial data.
   *
   * @param spIds the SP ids in this chunk
   * @param thresholds the eligibility gates for this run
   * @return evaluated/eligible counts for run totals
   */
  @ActivityMethod
  RoseOrderChunkResult processRoseOrderChunk(
      List<Integer> spIds, RoseOrderEligibilityThresholds thresholds);
}
