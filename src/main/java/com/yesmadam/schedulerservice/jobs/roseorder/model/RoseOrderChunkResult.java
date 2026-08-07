package com.yesmadam.schedulerservice.jobs.roseorder.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of one processed chunk, reported back to the workflow for run totals. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoseOrderChunkResult {

  /** Number of SPs evaluated in this chunk. */
  private int evaluated;

  /** Number of SPs that passed all eligibility gates. */
  private int eligible;

  /** Wall-clock time each per-chunk DAO call took, keyed by query name. */
  private Map<String, Long> queryDurationsMs;

  /** Row count returned by each per-chunk DAO call, keyed by query name. */
  private Map<String, Integer> queryRowCounts;

  public RoseOrderChunkResult(int evaluated, int eligible) {
    this(evaluated, eligible, new LinkedHashMap<>(), new LinkedHashMap<>());
  }
}
