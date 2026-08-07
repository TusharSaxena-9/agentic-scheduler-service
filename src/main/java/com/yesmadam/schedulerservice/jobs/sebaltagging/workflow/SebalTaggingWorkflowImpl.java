package com.yesmadam.schedulerservice.jobs.sebaltagging.workflow;

import java.time.Duration;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.crontiming.activity.CronTimingActivities;
import com.yesmadam.schedulerservice.jobs.sebaltagging.activity.SebalTaggingActivities;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.DateRangeChunk;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingChunkResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingRunContext;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingTierResult;

import org.slf4j.Logger;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the SEBAL sub-category tagging workflow.
 *
 * <p>Three phases: fetch the run context once (decides full vs. incremental and the date-range
 * chunks), fold each chunk into the running aggregate as its own retryable activity, then run a
 * single finalize activity that re-tiers the whole aggregate and advances the watermark. Ranking
 * happens exactly once, after every chunk has committed — never per-chunk — since a national
 * percentile split needs the fully-merged aggregate, not a partial one.
 *
 * <p>Pure orchestration only: all side effects (database) go through activities. Do not inject
 * Spring beans or use {@code @Value} here — workflow code must stay deterministic for Temporal
 * replay. Logging goes through {@link Workflow#getLogger} rather than a {@code @Slf4j} field: a
 * plain logger re-emits every line during replay, so a workflow that gets replayed a few times
 * duplicates its whole log history.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.sebaltagging}")
public class SebalTaggingWorkflowImpl implements SebalTaggingWorkflow {

  private static final Logger log = Workflow.getLogger(SebalTaggingWorkflowImpl.class);

  /**
   * Activity stub for the tagging run.
   *
   * <p>{@code startToCloseTimeout} bounds <em>one activity attempt</em> — a single chunk, or the
   * finalize call — never the whole run. Sized generously on purpose: with the heartbeat below
   * handling liveness, this is only a backstop against a pathological-but-still-heartbeating
   * attempt, and killing a healthy long one would be far worse than letting it finish. Reference
   * points: the slowest chunk on a healthy run is ~25s and finalize ~81s, but the slowest single
   * attempt ever observed was 284s — the 2024-03 chunk while the dominant-sub-category query was on
   * a bad plan. Plans have flipped twice on this dataset, so headroom here is cheap insurance.
   * (An hour was the old value and was genuinely wrong, but for a different reason: with no
   * heartbeat it was the <em>only</em> liveness signal, so a worker that died mid-chunk stalled the
   * run for a full hour before Temporal retried.)
   *
   * <p>{@code heartbeatTimeout} is what makes cancellation work at all. Temporal delivers activity
   * cancellation <em>through heartbeats</em> — without one, a cancelled workflow flips to CANCELED
   * immediately while the activity thread keeps running to completion on the worker, oblivious.
   * The activities call {@code heartbeat()} between their internal phases; see
   * {@code SebalTaggingActivitiesImpl}.
   *
   * <p>Three minutes rather than one because the heartbeat interval is bounded by the longest
   * single un-heartbeated call, and that is {@code insertSummaryLedgerRows} — one transaction over
   * every ranked pair, ~73s of the 80.8s finalize on the 2026-07-26 run. A one-minute timeout would
   * kill finalize mid-write. If that insert ever grows past ~2 minutes, raise this or push the
   * heartbeat down into the writer rather than discovering it as a spurious retry.
   */
  private final SebalTaggingActivities sebalTaggingActivities =
      Workflow.newActivityStub(
          SebalTaggingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(30))
              .setHeartbeatTimeout(Duration.ofMinutes(3))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /**
   * Activity stub for cron timing bookkeeping. Capped like {@link #sebalTaggingActivities} —
   * unlimited retries here would let a stuck cron-timing write (e.g. a task-queue registration
   * mismatch) hold the whole workflow "Running" forever, invisibly, without ever reaching the
   * actual tagging work below.
   */
  private final CronTimingActivities cronTimingActivities =
      Workflow.newActivityStub(
          CronTimingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /**
   * {@inheritDoc}
   *
   * <p>Cron timing is attempted but never fatal — a broken cron-timing write shouldn't block the
   * actual tagging work below from running. Both calls are wrapped so a failure here (even after
   * exhausting its own retry cap) is logged and swallowed rather than aborting the workflow.
   */
  @Override
  @Loggable(operation = "sebal-tagging-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    Long cronTimingId = saveCronStartQuietly();

    try {
      log.info("event=workflow_started workflow_id={}", workflowId);

      SebalTaggingRunContext runContext = sebalTaggingActivities.fetchSebalTaggingRunContext();

      int totalFirstOrders = 0;
      int totalPairsUpdated = 0;

      for (DateRangeChunk chunk : runContext.getChunks()) {
        SebalTaggingChunkResult result =
            sebalTaggingActivities.processSebalTaggingChunk(chunk, runContext.getRunMode());
        totalFirstOrders += result.getFirstOrdersProcessed();
        totalPairsUpdated += result.getPairsUpdated();
      }

      SebalTaggingTierResult tierResult =
          sebalTaggingActivities.finalizeSebalTaggingRun(
              runContext.getRunMode(), runContext.getNewProcessedUntil());

      log.info(
          "event=workflow_completed workflow_id={} run_mode={} chunks={} first_orders={} "
              + "pairs_updated={} sub_categories_tiered={} sps_tagged={}",
          workflowId,
          runContext.getRunMode(),
          runContext.getChunks().size(),
          totalFirstOrders,
          totalPairsUpdated,
          tierResult.getSubCategoriesTiered(),
          tierResult.getSpsTagged());

    } finally {
     Workflow.newDetachedCancellationScope(() -> saveCronEndQuietly(cronTimingId)).run();
    }
  }

  /** @return the cron timing id, or {@code null} if even 3 attempts couldn't record it. */
  private Long saveCronStartQuietly() {
    try {
      return cronTimingActivities.saveCronStart(
          "SebalTaggingWorkflow", "run", "Update SEBAL sub-category SP tags");
    } catch (RuntimeException e) {
      log.warn("event=save_cron_start_failed error={}", e.getMessage());
      return null;
    }
  }

  private void saveCronEndQuietly(Long cronTimingId) {
    if (cronTimingId == null) {
      return;
    }
    try {
      cronTimingActivities.saveCronEnd(cronTimingId);
    } catch (RuntimeException e) {
      log.warn("event=save_cron_end_failed cron_timing_id={} error={}", cronTimingId, e.getMessage());
    }
  }
}
