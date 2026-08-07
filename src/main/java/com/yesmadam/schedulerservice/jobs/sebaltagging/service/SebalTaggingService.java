package com.yesmadam.schedulerservice.jobs.sebaltagging.service;

import java.time.LocalDate;

import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.DateRangeChunk;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingChunkResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingRunContext;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingTierResult;

public interface SebalTaggingService {

  /**
   * Decides run mode from the watermark (first-ever run or a manually-forced rebuild is FULL,
   * everything else is INCREMENTAL) and builds the date-range chunks for this run.
   *
   * @return the run context for the workflow to iterate over
   */
  SebalTaggingRunContext fetchRunContext();

  /**
   * Folds one date-range chunk's qualifying first-order cohort into the running aggregate and
   * appends the per-chunk ledger delta rows, atomically.
   *
   * @param chunk the completion-date range to process
   * @param runMode the mode this run is operating in (stamped onto ledger rows)
   * @param runId the workflow run id to stamp onto each ledger row for this run
   * @param heartbeat invoked between phases to report liveness and pick up cancellation
   * @return counts for run totals
   */
  SebalTaggingChunkResult processChunk(
      DateRangeChunk chunk, SebalTaggingRunMode runMode, String runId, Runnable heartbeat);

  /**
   * Re-tiers the entire running aggregate, backfills this run's ledger rows with the final
   * cumulative/rate/tier/rank, and advances the watermark.
   *
   * @param runMode the mode this run operated in
   * @param runId the workflow run id shared by every row this run produced
   * @param newProcessedUntil the completion-date cutoff to persist as the new watermark
   * @param heartbeat invoked between batches to report liveness and pick up cancellation
   * @return counts for run totals
   */
  SebalTaggingTierResult finalizeRun(
      SebalTaggingRunMode runMode, String runId, LocalDate newProcessedUntil, Runnable heartbeat);
}
