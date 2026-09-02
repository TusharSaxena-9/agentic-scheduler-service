package com.yesmadam.schedulerservice.jobs.autoassign.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignOutcome;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of one processed chunk, reported back to the workflow for run totals. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AutoAssignChunkResult {

  /** Number of orders evaluated in this chunk. */
  private int evaluated;

  /** How many orders landed on each outcome. */
  private Map<AutoAssignOutcome, Integer> outcomeCounts;

  /**
   * Orders this chunk did not auto-assign, to be handed to Auto Job Distribution. Returned to the
   * workflow rather than dispatched here so the whole run's candidates are dispatched together,
   * matching the single {@code triggerDistributionBatch} call the cron makes per tick.
   */
  private List<Long> distributionOrderIds;

  /** Wall-clock time each per-chunk DAO call took, keyed by query name. */
  private Map<String, Long> queryDurationsMs;

  /** Row count returned by each per-chunk DAO call, keyed by query name. */
  private Map<String, Integer> queryRowCounts;

  public AutoAssignChunkResult(
      int evaluated,
      Map<AutoAssignOutcome, Integer> outcomeCounts,
      List<Long> distributionOrderIds) {
    this(
        evaluated,
        outcomeCounts,
        distributionOrderIds,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  /** An empty result — no orders in the chunk, nothing dispatched. */
  public static AutoAssignChunkResult empty() {
    return new AutoAssignChunkResult(0, new EnumMap<>(AutoAssignOutcome.class), new ArrayList<>());
  }

  /** Count for one outcome, or zero when the chunk produced none. */
  public int count(AutoAssignOutcome outcome) {
    return outcomeCounts == null ? 0 : outcomeCounts.getOrDefault(outcome, 0);
  }
}
