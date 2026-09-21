package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.activity;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity contract for the 20:00 kit-approved notification job.
 *
 * <p><b>Every method names its activity type explicitly</b>, for the reason spelled out on {@link
 * SpAvailabilityAutoOnActivities}: activity type names are global to the worker, and this job
 * shares the {@code light} worker with that one. Prefixing every type with the job is what keeps a
 * method added here from colliding with one added there at startup.
 */
@ActivityInterface
public interface SpKitApprovedNotifyActivities {

  /**
   * Resolves the window this run covers: yesterday in Asia/Kolkata, whole day, inclusive both ends.
   *
   * <p>The same window the 23:30 availability job resolves on the same date, which is what makes
   * the two jobs act on one cohort. Its own activity because workflow code cannot read a clock
   * (rule 1), and resolved once rather than per batch so a run spanning midnight cannot query two
   * days.
   *
   * @return the window, plus the timestamp every marker row this run writes is stamped with
   */
  @ActivityMethod(name = "SpKitNotifyResolveWindow")
  SpAvailabilityAutoOnWindow resolveRunWindowNotification();

  /**
   * Claims the next page of SPs for notification, in one committed transaction, and returns the
   * ones newly claimed.
   *
   * <p>Claiming is what makes this job converge. It changes nothing the eligibility query reads
   * otherwise, so without a marker a retry would re-push every SP it had already pushed.
   *
   * @param window the window from {@link #resolveRunWindow()}
   * @param afterSpId exclusive keyset cursor; {@code 0} for the first page
   * @return newly claimed ids (bounded by batch size), the next cursor, and whether the window is
   *     exhausted
   */
  @ActivityMethod(name = "SpKitNotifyClaimBatch")
  SpKitCohortBatch claimNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId);

  /**
   * Pushes the CleverTap heads-up to SPs whose kit was approved yesterday.
   *
   * <p>Separate from {@link #claimNextBatch} on purpose (rule 14): a CleverTap upload cannot be
   * rolled back, so it must not share a retry boundary with the claim. A retry of this activity
   * re-sends the same list, which is correct — it is retried because the send failed.
   *
   * <p>A 4xx is classified non-retryable: a bad account id or malformed payload cannot succeed on a
   * second attempt, and three attempts against it only delay the failure (rule 4).
   *
   * @param spIds SPs just claimed, already bounded to one batch
   * @return number of events sent
   */
  @ActivityMethod(name = "SpKitNotifySend")
  int notifyKitApproved(List<Integer> spIds);
}
