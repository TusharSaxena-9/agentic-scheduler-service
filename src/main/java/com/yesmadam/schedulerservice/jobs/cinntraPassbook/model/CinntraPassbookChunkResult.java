package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/** Counts for one chunk, small enough to carry in workflow history (CLAUDE.md rule 9). */
@Data
public class CinntraPassbookChunkResult {

  /** Posted to SAP (or, for the journal sync, counted as a success exactly where tracking counts one). */
  private int succeeded;

  /** A SAP call failed, or a lookup tracking treats as fatal for the entry was missing. */
  private int failed;

  /** Not eligible — franchise hub, excluded category, laser/BT SP, no series, no account code. Nothing sent. */
  private int skipped;

  /** Already posted, by an earlier attempt of this chunk or another path. Nothing sent. */
  private int alreadyPosted;

  /** The passbook row is gone. */
  private int notFound;

  /** Per query name, from {@code QueryTimer} (rule 15). One entry per named query, never per row. */
  private Map<String, Long> queryDurationsMs = new LinkedHashMap<>();

  private Map<String, Integer> queryRowCounts = new LinkedHashMap<>();
}
