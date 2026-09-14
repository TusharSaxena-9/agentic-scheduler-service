package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of processing one window (day-offset), reported back to the workflow.
 *
 * <p>Bounded by design (CLAUDE.md rule 9) — a count, never the candidate list or any JPA entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletExpiryReminderChunkResult {

  /** Candidates this window fetched and published. */
  private int candidateCount;

  /** Wall-clock time of the candidate query (CLAUDE.md rule 15). */
  private Map<String, Long> queryDurationsMs;

  /** Row count of the candidate query. */
  private Map<String, Integer> queryRowCounts;

  public WalletExpiryReminderChunkResult(int candidateCount) {
    this(candidateCount, new LinkedHashMap<>(), new LinkedHashMap<>());
  }
}
