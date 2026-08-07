package com.yesmadam.schedulerservice.jobs.sebaltagging.activity;

import java.time.LocalDate;

import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.DateRangeChunk;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingChunkResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingRunContext;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingTierResult;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for the SEBAL sub-category tagging run.
 *
 * <p>Three activities, run in this order by the workflow: one context fetch (decides run mode and
 * chunk boundaries), one call per date-range chunk (folds that chunk's cohort into the running
 * aggregate — no ranking yet), then one finalize call after every chunk has committed (re-tiers
 * the whole aggregate table in a single pass and advances the watermark). Ranking only happens
 * once, over the fully-merged aggregate, so a chunk failure never leaves a partial ranking.
 */
@ActivityInterface
public interface SebalTaggingActivities {

  /**
   * Decides run mode from the current watermark and builds the date-range chunks for this run.
   *
   * @return the run context the workflow iterates over
   */
  @ActivityMethod
  SebalTaggingRunContext fetchSebalTaggingRunContext();

  /**
   * Folds one date-range chunk's qualifying first-order cohort into the running aggregate. Atomic:
   * the aggregate upsert and ledger delta rows for this chunk commit in a single transaction, so a
   * retry of this activity never double-counts or leaves partial data.
   *
   * @param chunk the completion-date range to process
   * @param runMode the mode this run is operating in (stamped onto ledger rows)
   * @return counts for run totals
   */
  @ActivityMethod
  SebalTaggingChunkResult processSebalTaggingChunk(DateRangeChunk chunk, SebalTaggingRunMode runMode);

  /**
   * Re-tiers the entire running aggregate (one {@code NTILE(3)} pass per sub-category), appends
   * the final ledger rows, and advances the watermark to {@code newProcessedUntil}. Called once,
   * after every chunk in the run has committed.
   *
   * @param runMode the mode this run operated in
   * @param newProcessedUntil the completion-date cutoff to persist as the new watermark
   * @return counts for run totals
   */
  @ActivityMethod
  SebalTaggingTierResult finalizeSebalTaggingRun(
      SebalTaggingRunMode runMode, LocalDate newProcessedUntil);
}
