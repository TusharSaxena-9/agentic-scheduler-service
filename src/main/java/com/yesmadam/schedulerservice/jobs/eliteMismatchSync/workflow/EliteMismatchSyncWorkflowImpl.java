package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.workflow;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.activity.EliteMismatchSyncActivities;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncChunkResult;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncRunContext;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the elite-fee-mismatch-to-SAP sync workflow, on the heavy tier like every SAP
 * job.
 *
 * <p>Shape: one prepare activity resolves the run (tracking's selection query plus the fee map and
 * plan amount it resolves once per run), then the workflow walks the subscription ids in chunks of
 * {@link #CHUNK_SIZE}, one retryable activity per chunk. A chunk that still fails after its retries
 * is logged and the remaining chunks still run — tracking also kept going past a failure — and the
 * run then fails, so the schedule's pause-on-failure catches it.
 *
 * <p>Pure orchestration — all side effects (database, HTTP) go through activities. Do not inject
 * Spring beans or use {@code @Value} here; workflow code must stay deterministic for Temporal
 * replay. Logging goes through {@link Workflow#getLogger} rather than a {@code @Slf4j} field: a
 * plain logger re-emits every line on replay.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.heavy}")
public class EliteMismatchSyncWorkflowImpl implements EliteMismatchSyncWorkflow {

  private static final Logger log = Workflow.getLogger(EliteMismatchSyncWorkflowImpl.class);

  /**
   * Subscriptions per chunk activity. Each one is up to three SAP calls (BusinessPartner, Invoice,
   * IncomingPayment), so a chunk of 20 is a few minutes even when SAP is slow — short enough that a
   * retry repeats little and a heavy slot is not held for long.
   */
  private static final int CHUNK_SIZE = 20;

  /**
   * Placeholder sizing until one real run has been observed (CLAUDE.md rule 4).
   *
   * <p>Default (prepare): one bounded read over a single day — the "query over a bounded set" row.
   */
  private final EliteMismatchSyncActivities prepareActivities =
      Workflow.newActivityStub(
          EliteMismatchSyncActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /**
   * Chunk: the "one chunk of a chunked job" row. The service heartbeats once per subscription, and
   * one subscription is at most three SAP calls, so 5 minutes of silence means it is stuck. Three
   * attempts are safe because the service skips subscriptions that already have a SAP doc entry.
   * scheduleToClose covers 3 x 30m plus backoff.
   */
  private final EliteMismatchSyncActivities chunkActivities =
      Workflow.newActivityStub(
          EliteMismatchSyncActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(30))
              .setHeartbeatTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofHours(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /** {@inheritDoc} */
  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    EliteMismatchSyncRunContext context = prepareActivities.prepareEliteMismatchRun();
    List<Integer> ids = context.getSubscriptionIds();
    if (ids == null || ids.isEmpty()) {
      log.info("event=workflow_completed workflow_id={} reason=no_subscriptions", workflowId);
      return;
    }

    int succeeded = 0;
    int failed = 0;
    int alreadySynced = 0;
    int notFound = 0;
    int failedChunks = 0;
    int chunks = 0;

    for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
      List<Integer> chunk = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
      chunks++;
      try {
        EliteMismatchSyncChunkResult result =
            chunkActivities.syncEliteMismatchChunk(
                chunk, context.getEliteFeeBySubscriptionId(), context.getFallbackPlanAmount());
        succeeded += result.getSucceeded();
        failed += result.getFailed();
        alreadySynced += result.getAlreadySynced();
        notFound += result.getNotFound();
      } catch (ActivityFailure ex) {
        failedChunks++;
        log.error(
            "event=elite_mismatch_chunk_failed workflow_id={} chunk_start={} chunk_size={} error={}",
            workflowId,
            from,
            chunk.size(),
            ex.getMessage());
      }
    }

    log.info(
        "event=workflow_completed workflow_id={} subscriptions={} chunks={} failed_chunks={} "
            + "succeeded={} failed={} already_synced={} not_found={}",
        workflowId,
        ids.size(),
        chunks,
        failedChunks,
        succeeded,
        failed,
        alreadySynced,
        notFound);

    if (failedChunks > 0) {
      throw ApplicationFailure.newNonRetryableFailure(
          failedChunks + " of " + chunks + " elite mismatch chunks failed", "EliteMismatchChunkFailed");
    }
  }
}
