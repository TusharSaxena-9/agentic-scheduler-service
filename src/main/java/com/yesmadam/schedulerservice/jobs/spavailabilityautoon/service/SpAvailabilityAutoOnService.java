package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import java.time.LocalDate;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

/**
 * Business logic for the auto SP-availability-on job. Imports nothing from {@code io.temporal}
 * (CLAUDE.md rule 12 and migration checklist rule 5), so it is tested with plain JUnit and no test
 * environment, and the job is movable.
 */
public interface SpAvailabilityAutoOnService {

  /**
   * The window for the <b>23:30 availability flip</b> on {@code runDate}: D-2, whole day, inclusive
   * at both ends — {@code D-2 00:00:00} through {@code D-2 23:59:59}.
   *
   * <p><b>The two jobs use different offsets, and that is the whole schedule of the flow.</b> A kit
   * approved on Monday is picked up by {@link #resolveWindowNotification} on Tuesday at 20:00 (D-1
   * from Tuesday) and by this window on Wednesday at 23:30 (D-2 from Wednesday). Same cohort, but
   * the push lands roughly 27 hours before the SP is actually switched on — not the same evening.
   *
   * <p>Because the offsets differ, the two jobs are coupled across consecutive days rather than
   * within one. Changing either offset without the other silently breaks that pairing: the notify
   * job would announce one cohort and the flip job switch on a different one, with nothing failing.
   *
   * <p>Inclusive at both ends because the eligibility query filters with {@code BETWEEN}; the end
   * is the last second of the day rather than midnight of the next, so no approval falls in two
   * consecutive windows.
   *
   * <p><b>This tiles only if {@code approvedat} stores whole seconds.</b> At zero fractional-second
   * precision — the default for a MySQL {@code timestamp}/{@code datetime} column — {@code
   * 23:59:59} is the last representable instant of the day and consecutive windows are contiguous.
   * If the column is ever widened to {@code timestamp(3)} or {@code (6)}, approvals in the final
   * fraction of a second fall into no window at all, and this must become a half-open {@code >= /
   * <} bound.
   *
   * @param runDate the run's own date in Asia/Kolkata. Passed in rather than read from the clock
   *     here so the window is a decision the caller states, and the boundary cases are testable.
   * @return the window, plus the single timestamp every write in the run is stamped with
   */
  SpAvailabilityAutoOnWindow resolveWindow(LocalDate runDate);

  /**
   * Flips the next page of eligible SPs and reports which ones actually changed.
   *
   * <p>One page, one committed transaction (rule 14) — the caller drives the loop, so a failure
   * costs one batch rather than the run, and the batches before it stay committed.
   *
   * @param window the window resolved once for this run
   * @param afterSpId exclusive keyset cursor; {@code 0} for the first batch
   * @return the flipped ids, the cursor for the next call, and whether the window is exhausted
   */
  SpKitCohortBatch flipNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId);

  /**
   * The window for the <b>20:00 notification</b> on {@code runDate}: D-1, whole day, inclusive at
   * both ends — {@code D-1 00:00:00} through {@code D-1 23:59:59}.
   *
   * <p>One day ahead of {@link #resolveWindow}, which is what puts the push before the flip: an SP
   * approved on Monday is told on Tuesday evening and switched on late Wednesday. See {@link
   * #resolveWindow} for why the pairing is load-bearing and what breaks if one offset moves.
   *
   * <p>Same inclusive-both-ends bound, and the same dependency on {@code approvedat} storing whole
   * seconds, as {@link #resolveWindow}.
   *
   * @param runDate the run's own date in Asia/Kolkata. Passed in rather than read from the clock
   *     here so the window is a decision the caller states, and the boundary cases are testable.
   * @return the window, plus the single timestamp every marker row this run writes is stamped with
   */
  SpAvailabilityAutoOnWindow resolveWindowNotification(LocalDate runDate);
}
