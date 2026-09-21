package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.activity;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity contract for the 23:30 auto SP-availability-on job.
 *
 * <p><b>Every method names its activity type explicitly.</b> A Temporal activity type name is
 * global to the worker, not scoped to the interface that declares it, and this job shares the
 * {@code light} worker with {@link SpKitApprovedNotifyActivities}. Two interfaces on one worker
 * with a method of the same name — {@code resolveRunWindow}, say — collide at startup with {@code
 * TypeAlreadyRegisteredException}, which fails the whole application context, not just this job.
 *
 * <p>Relying on the default (the Java method name, capitalised) makes that collision a property of
 * two files that never mention each other. Prefixing every type with the job removes the coupling:
 * a new method here cannot clash with one added there. {@code ActivityTypeUniquenessTest} fails the
 * build if it ever does.
 *
 * <p>These names are also the keys of the per-method {@code ActivityOptions} map in {@link
 * com.yesmadam.schedulerservice.jobs.spavailabilityautoon.workflow.SpAvailabilityAutoOnWorkflowImpl}
 * — a key that matches no activity type is silently ignored rather than rejected, so the two must
 * be changed together.
 */
@ActivityInterface
public interface SpAvailabilityAutoOnActivities {

  /**
   * Resolves the window this run covers: yesterday in Asia/Kolkata, whole day, inclusive at both
   * ends.
   *
   * <p>Its own activity because workflow code cannot read a clock (CLAUDE.md rule 1), and resolving
   * it once rather than per batch stops a run that spans midnight from querying two different days.
   * Temporal records the result in history, so a replay reuses the original values rather than
   * recomputing a window that has since slid forward.
   *
   * @return the window, plus the timestamp every write in this run is stamped with
   */
  @ActivityMethod(name = "SpAvailabilityResolveWindow")
  SpAvailabilityAutoOnWindow resolveRunWindow();

  /**
   * Flips the next page of eligible SPs on, in one committed transaction, and reports which ones
   * actually changed.
   *
   * @param window the window from {@link #resolveRunWindow()}
   * @param afterSpId exclusive keyset cursor; {@code 0} for the first batch
   * @return flipped ids (bounded by the configured batch size), the next cursor, and whether the
   *     window is exhausted
   */
  @ActivityMethod(name = "SpAvailabilityFlipBatch")
  SpKitCohortBatch flipNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId);
}
