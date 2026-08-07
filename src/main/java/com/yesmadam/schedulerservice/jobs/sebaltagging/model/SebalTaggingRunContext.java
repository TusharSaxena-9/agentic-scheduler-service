package com.yesmadam.schedulerservice.jobs.sebaltagging.model;

import java.time.LocalDate;
import java.util.List;

import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the workflow needs to drive one tagging run: whether it's rebuilding the aggregate
 * from scratch or folding in a delta, the date-range chunks to process, and the cutoff to persist
 * as the new watermark once every chunk has committed. Fetched by a single activity so these
 * values are recorded in workflow history and stay stable across retries and replays.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SebalTaggingRunContext {

  private SebalTaggingRunMode runMode;

  /** Date-range chunks to process, in order. Empty means nothing new has closed since last run. */
  private List<DateRangeChunk> chunks;

  /**
   * The completion-date cutoff this run will advance the watermark to once all chunks succeed —
   * always {@code today - 90 days}, i.e. the newest completion date whose rebook window has fully
   * closed as of today.
   */
  private LocalDate newProcessedUntil;
}
