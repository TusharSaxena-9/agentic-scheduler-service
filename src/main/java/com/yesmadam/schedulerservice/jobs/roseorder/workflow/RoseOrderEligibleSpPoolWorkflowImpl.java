package com.yesmadam.schedulerservice.jobs.roseorder.workflow;

import java.time.Duration;
import java.util.List;

import com.google.common.collect.Lists;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.crontiming.activity.CronTimingActivities;
import com.yesmadam.schedulerservice.jobs.roseorder.activity.RoseOrderActivities;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderChunkResult;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderRunContext;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of the rose order eligible SP pool update workflow.
 *
 * <p>The workflow drives the chunking: it fetches the run context once, then processes each chunk
 * as its own activity. Completed chunks are recorded in workflow history, so a chunk failure or
 * worker crash resumes from the failed chunk instead of re-running the whole pool — the activity
 * retry boundary matches the per-chunk database transaction boundary.
 *
 * <p>Pure orchestration only: all side effects (database, HTTP) go through activities. Do not
 * inject Spring beans or use {@code @Value} here — workflow code must stay deterministic for
 * Temporal replay.
 */
@Slf4j
@WorkflowImpl(taskQueues = "${temporal.taskqueue.heavy}")
public class RoseOrderEligibleSpPoolWorkflowImpl implements RoseOrderEligibleSpPoolWorkflow {

  /** SPs evaluated per chunk activity; each chunk is one retryable, transactional step. */
  private static final int CHUNK_SIZE = 100;

  /**
   * Activity stub for the eligibility run. Timeout covers one chunk (or the context fetch), not the
   * whole pool. Attempts are capped so a persistent failure fails the workflow loudly instead of
   * retrying forever and silently blocking the next scheduled run.
   */
  private final RoseOrderActivities roseOrderActivities =
      Workflow.newActivityStub(
          RoseOrderActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /** Activity stub for cron timing operations with 2-minute timeout. */
  private final CronTimingActivities cronTimingActivities =
      Workflow.newActivityStub(
          CronTimingActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build());

  /**
   * {@inheritDoc}
   *
   * <p>Cron timing is always recorded, even if the main workflow logic fails.
   */
  @Override
  @Loggable(operation = "rose-order-eligible-sp-pool-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    // Record workflow start time for monitoring
    Long cronTimingId =
        cronTimingActivities.saveCronStart(
            "RoseOrderEligibleSpPoolWorkflow", "run", "Update rose order eligible SP pool");

    try {
      log.info("event=workflow_started workflow_id={}", workflowId);

      RoseOrderRunContext runContext = roseOrderActivities.fetchRoseOrderRunContext();

      int totalEvaluated = 0;
      int totalEligible = 0;

      for (List<Integer> chunk : Lists.partition(runContext.getSpIds(), CHUNK_SIZE)) {
        RoseOrderChunkResult result =
            roseOrderActivities.processRoseOrderChunk(chunk, runContext.getThresholds());
        totalEvaluated += result.getEvaluated();
        totalEligible += result.getEligible();
      }

      log.info(
          "event=workflow_completed workflow_id={} evaluated={} eligible={}",
          workflowId,
          totalEvaluated,
          totalEligible);

    } finally {
      // Always record workflow end time, even on failure
      cronTimingActivities.saveCronEnd(cronTimingId);
    }
  }
}
