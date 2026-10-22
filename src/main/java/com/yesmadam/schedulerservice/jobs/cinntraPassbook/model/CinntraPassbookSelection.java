package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The passbook ids one run will post, in tracking's processing order, plus the window that selected
 * them — returned from the activity so the run records the window it actually queried (CLAUDE.md
 * rule 1).
 *
 * <p>Bounded payload (rule 9): ids only, selected by a 2-hour window for the journal sync and by one
 * IST day of a single reason code for the daily kinds. Every detail the post needs is loaded per
 * chunk inside the chunk activity, never carried through workflow history.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CinntraPassbookSelection {

  private List<Long> passbookIds;

  /** Inclusive start of the selection window, {@code yyyy-MM-dd HH:mm:ss} IST. */
  private String windowStart;

  /** Exclusive end of the selection window, {@code yyyy-MM-dd HH:mm:ss} IST. */
  private String windowEnd;

  /** Per query name, from {@code QueryTimer} (rule 15). */
  private Map<String, Long> queryDurationsMs;

  private Map<String, Integer> queryRowCounts;
}
