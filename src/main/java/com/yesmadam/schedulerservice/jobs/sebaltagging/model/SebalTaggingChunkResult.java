package com.yesmadam.schedulerservice.jobs.sebaltagging.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of one processed date-range chunk, reported back to the workflow for run totals. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SebalTaggingChunkResult {

  /** Qualifying first-order completions found in this chunk's date range. */
  private int firstOrdersProcessed;

  /** Distinct (sp, sub-category) pairs whose running aggregate this chunk updated. */
  private int pairsUpdated;

  /** Wall-clock time each per-chunk DAO call took, keyed by query name. */
  private Map<String, Long> queryDurationsMs;

  /** Row count returned by each per-chunk DAO call, keyed by query name. */
  private Map<String, Integer> queryRowCounts;

  public SebalTaggingChunkResult(int firstOrdersProcessed, int pairsUpdated) {
    this(firstOrdersProcessed, pairsUpdated, new LinkedHashMap<>(), new LinkedHashMap<>());
  }
}
