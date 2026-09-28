package com.yesmadam.schedulerservice.jobs.ozuzuorder.model;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One run's search result: the window queried, and the order codes Unicommerce reported under
 * each date-type filter.
 *
 * <p>Kept as two separate lists rather than one deduped set — matching the messagingapis original,
 * which ran two independent searches and fed each into its own create-only / update-only branch.
 * {@code createdSearchCodes} only ever creates a row (skipping any code already in {@code
 * tbl_ozuzu_order}); {@code updatedSearchCodes} only ever refreshes a row that already exists,
 * skipping anything it doesn't recognize. Merging the two into one set would let an update-only
 * code create a row the original never would (see {@code OzuzuOrderSyncServiceImpl}).
 *
 * <p>Lists of codes, not of full orders — an unbounded list of {@code OzuzuOrder} riding across
 * the activity boundary would grow with the table and blow the 2 MB payload / 51,200 history-event
 * limits (rule 9 in CLAUDE.md). Detail is fetched per code by the create/update activities.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OzuzuOrderCodesBatch {

  private Timestamp windowStart;
  private Timestamp windowEnd;
  private List<String> createdSearchCodes;
  private List<String> updatedSearchCodes;

  /** Wall-clock time each Unicommerce search call took, keyed by query name (rule 15). */
  private Map<String, Long> queryDurationsMs;

  /** Row count each Unicommerce search call returned, keyed by query name (rule 15). */
  private Map<String, Integer> queryRowCounts;

  public OzuzuOrderCodesBatch(
      Timestamp windowStart,
      Timestamp windowEnd,
      List<String> createdSearchCodes,
      List<String> updatedSearchCodes) {
    this(
        windowStart,
        windowEnd,
        createdSearchCodes,
        updatedSearchCodes,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }
}
