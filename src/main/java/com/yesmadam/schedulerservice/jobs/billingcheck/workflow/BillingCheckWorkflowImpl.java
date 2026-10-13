package com.yesmadam.schedulerservice.jobs.billingcheck.workflow;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.billingcheck.activity.BillingCheckActivities;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckChunk;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckSendResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Orchestration only: walks the window one keyset page at a time — fetch a page, send it, move the
 * cursor — holding one page and a cursor, never the day's list (CLAUDE.md rule 9).
 *
 * <p>The fetch and the send have opposite retry needs, hence two option sets on one stub (the
 * per-activity map, which merges with the default): the fetch is a read and retries; the send cannot
 * be taken back and there is no sent-marker, so it runs once.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class BillingCheckWorkflowImpl implements BillingCheckWorkflow {

  private static final Logger log = Workflow.getLogger(BillingCheckWorkflowImpl.class);

  /** Guard against a cursor that stops advancing — 1000 pages is far beyond a real day. */
  static final int MAX_CHUNKS = 1000;

  /**
   * Placeholder sizing, not yet measured on prod. Fetch: one bounded indexed read, 2m per attempt,
   * 3 attempts. Send ("SendChunk"): ~100 sequential provider calls, 5m start-to-close, heartbeat per
   * order so a hung call is caught within 2m, and exactly one attempt — a retry would re-message
   * every customer the first attempt already reached (rule 14).
   */
  private final BillingCheckActivities activities =
      Workflow.newActivityStub(
          BillingCheckActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(10))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "SendChunk",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(5))
                  .setHeartbeatTimeout(Duration.ofMinutes(2))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(6))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    // Fixed once for the whole run so every page derives the same window.
    long nominalFireTimeMillis = Workflow.currentTimeMillis();
    log.info("event=workflow_started workflow_id={} nominal_fire_time_millis={}", workflowId, nominalFireTimeMillis);

    int totalSent = 0;
    int totalFailed = 0;
    long cursor = 0;
    boolean done = false;

    for (int chunk = 0; !done; chunk++) {
      if (chunk >= MAX_CHUNKS) {
        // ApplicationFailure, not a plain exception: a plain one would wedge the run instead of
        // failing it (rule 11).
        throw ApplicationFailure.newNonRetryableFailure(
            "billing-check cursor did not reach the end within " + MAX_CHUNKS + " chunks",
            "BillingCheckRunaway");
      }
      BillingCheckChunk page = activities.fetchChunk(nominalFireTimeMillis, cursor);
      if (!page.getOrders().isEmpty()) {
        BillingCheckSendResult result = activities.sendChunk(page);
        totalSent += result.getSent();
        totalFailed += result.getFailed();
      }
      cursor = page.getNextCursor();
      done = page.isDone();
    }

    log.info("event=workflow_completed workflow_id={} sent={} failed={}", workflowId, totalSent, totalFailed);
  }
}
