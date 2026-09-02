package com.yesmadam.schedulerservice.jobs.spNotReachedHome.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.spNotReachedHome.activity.SpNotReachedHomeActivities;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Orchestrates the tracker's three steps in the order the original ran them.
 *
 * <p>Pure orchestration — all database work goes through activities. Do not inject Spring beans or
 * use {@code @Value} here; workflow code must stay deterministic for Temporal replay. Logging goes
 * through {@link Workflow#getLogger} rather than a {@code @Slf4j} field: a plain logger re-emits
 * every line on replay.
 *
 * <p><b>Why the order matters:</b> {@code removeSpWithActiveJob} must run before {@code
 * insertSpNotReachedHome}. If it ran after, an SP who picked up a new job would have their row
 * refreshed by the UPSERT and then deleted, or worse, re-inserted by the next cycle — the original
 * sequences them this way for exactly that reason.
 *
 * <p>The original wrapped all three in one {@code try/catch} that swallowed everything, so a
 * failure in step 1 silently skipped steps 2 and 3 and the cron-timing row was never closed. Here
 * each step is its own activity with its own retries, and cron-end is always recorded.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpNotReachedHomeWorkflowImpl implements SpNotReachedHomeWorkflow {

  private static final Logger log = Workflow.getLogger(SpNotReachedHomeWorkflowImpl.class);

  /**
   * Sized from a measurement, not a guess: the 26 Aug 2026 UAT run completed all three steps in
   * <b>837 ms</b>. Each step is a handful of statements over a 10-minute slice of {@code
   * tbl_sp_location_lasthour}, and the table holds one row per SP (~12.5k), so the work is bounded
   * and small. Two minutes is therefore a wide backstop against a bad plan, not a target — and it
   * keeps a stuck run from overlapping the next 5-minute fire.
   *
   * <p>Three attempts, because a transient lock-wait timeout on the tracker table is worth retrying
   * while a persistent failure should surface. {@code scheduleToClose} is set as well as {@code
   * startToClose}: 3 x 2m plus backoff fits inside 8m, and without it the final retry could be
   * silently truncated (rule 4).
   *
   * <p><b>No heartbeat, deliberately.</b> Rule 10's threshold is ~1 minute and the measured run is
   * three orders of magnitude under it, with each step a single statement that either returns or
   * fails. A heartbeat timeout without heartbeat calls fails the activity every time at that
   * interval, so adding one here would be a new failure mode rather than a safeguard. Revisit if a
   * step ever grows an internal loop.
   */
  private final SpNotReachedHomeActivities activities =
      Workflow.newActivityStub(
          SpNotReachedHomeActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(8))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /**
   * {@inheritDoc}
   *
   * <p>Cron timing is attempted but never fatal — a broken cron-timing write shouldn't stop the
   * tracker.
   */
  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    log.info("event=workflow_started workflow_id={}", workflowId);

    int removed = activities.removeSpWithActiveJob();
    int upserted = activities.insertSpNotReachedHome();
    int marked = activities.autoMarkReachedHome();

    log.info(
        "event=workflow_completed workflow_id={} removed={} upserted={} marked_home={}",
        workflowId,
        removed,
        upserted,
        marked);
  }
}
