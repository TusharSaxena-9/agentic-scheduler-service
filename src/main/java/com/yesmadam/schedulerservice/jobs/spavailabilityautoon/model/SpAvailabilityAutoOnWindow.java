package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model;

import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The window one run operates over, resolved once by an activity and then held by the workflow for
 * the whole run.
 *
 * <p>Whole day, inclusive at both ends. <b>Which</b> day depends on the job: the 20:00 notify
 * resolves D-1, the 23:30 flip resolves D-2. So a kit approved on Monday is announced to the SP on
 * Tuesday evening and switched on late on Wednesday — same cohort, a day apart.
 *
 * <p>Resolved in an activity rather than recomputed per batch for two reasons. Workflow code cannot
 * read a clock at all (CLAUDE.md rule 1). And a run that spans midnight — a slow start, a long
 * retry — would otherwise have its later batches silently querying a different day from its earlier
 * ones, reporting a window it only half used.
 *
 * <p>Small and fixed-size, so holding it across the workflow's batch loop costs three values in
 * history regardless of how many SPs the run touches (rule 9).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpAvailabilityAutoOnWindow {

  /** Inclusive lower bound on {@code approvedat} — 00:00:00 IST of the job's own target day. */
  private Timestamp windowStart;

  /**
   * Inclusive upper bound on {@code approvedat} — yesterday 23:59:59 IST. Inclusive, not exclusive:
   * the query filters with {@code BETWEEN}, so the bound is the last second of the day rather than
   * midnight of the next one.
   */
  private Timestamp windowEnd;

  /**
   * Stamped on every {@code tbl_sp.updated_at} and every history row this run writes, so one run
   * reads as one action on an SP's timeline rather than as a scatter of per-batch times.
   */
  private Timestamp actionAt;
}
