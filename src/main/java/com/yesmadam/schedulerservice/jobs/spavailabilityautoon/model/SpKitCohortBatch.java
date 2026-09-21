package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One page of the SP cohort a run is acting on, handed back to the workflow.
 *
 * <p>Shared by both jobs in this package — the 8pm notify and the 11:30pm flip walk the same cohort
 * with the same keyset loop, and differ only in what they do with a page. One model rather than two
 * identical ones keeps that symmetry visible.
 *
 * <p>This is the one payload in the job that carries ids rather than only counts, and it is worth
 * being explicit about why that is still within CLAUDE.md rule 9. The list is capped by {@code
 * sp.availability.auto-on.batch-size} — a configuration constant, not the size of the table — so a
 * run over ten SPs and a run over ten thousand produce the same maximum payload; only the number of
 * batches differs. Rule 9 permits returning ids ("return IDs, a cursor, or a small DTO"); what it
 * forbids is a result that grows with the data, which this cannot.
 *
 * <p>The ids have to cross the boundary at all because the CleverTap send is a separate activity
 * (rule 14 — outbound sends go last, in their own activity, after the write commits). The
 * alternative, sending from inside the flip activity, would put an unrollbackable outbound call
 * inside the same retry boundary as the database write.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpKitCohortBatch {

  /**
   * The SPs this batch actually acted on — flipped, for the availability job; newly claimed, for
   * the notify job. Never longer than the configured batch size, and shorter than {@link
   * #candidateCount} whenever the writer's re-check found an SP another run had already handled.
   */
  private List<Integer> spIds;

  /** SP ids the reader offered for this batch, before the writer's re-check narrowed them. */
  private int candidateCount;

  /**
   * Highest SP id seen in this batch — the next batch's keyset cursor. {@code 0} when the batch was
   * empty, in which case the workflow stops rather than using it.
   */
  private int lastSpId;

  /**
   * True when the reader returned a short page, meaning the window holds nothing further and the
   * workflow can stop without paying for a round-trip that would come back empty.
   */
  private boolean exhausted;

  /** Milliseconds spent in each named query for this batch — one entry per query (rule 15). */
  private Map<String, Long> queryDurationsMs;

  /** Rows each named query returned or touched for this batch. */
  private Map<String, Integer> queryRowCounts;
}
