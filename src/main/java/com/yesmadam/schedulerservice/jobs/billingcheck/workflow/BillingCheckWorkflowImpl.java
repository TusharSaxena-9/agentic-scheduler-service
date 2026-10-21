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
 * Orchestration only: fetch a page, send it, move the cursor. The fetch retries; the send runs once
 * because there is no sent-marker (see BillingCheckService).
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class BillingCheckWorkflowImpl implements BillingCheckWorkflow {

  private static final Logger log = Workflow.getLogger(BillingCheckWorkflowImpl.class);

  /** Guard against a cursor that never advances. */
  static final int MAX_CHUNKS = 1000;

  /**
   * Placeholder timeouts, sized for 5k+ orders/day. Fetch: ~1.3s warm but >2m cold on prod, so 10m
   * x 3 attempts. Send: ~100 sequential calls per page, 10m, one attempt (a retry would re-message),
   * heartbeat per order. Total run time is bounded by run-timeout in schedules.yml.
   */
  private final BillingCheckActivities activities =
      Workflow.newActivityStub(
          BillingCheckActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              .setScheduleToCloseTimeout(Duration.ofMinutes(35))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "SendChunk",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(10))
                  .setHeartbeatTimeout(Duration.ofMinutes(2))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(11))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    // Fixed once so every page uses the same window.
    long nominalFireTimeMillis = Workflow.currentTimeMillis();
    log.info("event=workflow_started workflow_id={} nominal_fire_time_millis={}", workflowId, nominalFireTimeMillis);

    int totalSent = 0;
    int totalFailed = 0;
    long cursor = 0;
    boolean done = false;

    for (int chunk = 0; !done; chunk++) {
      if (chunk >= MAX_CHUNKS) {
        // ApplicationFailure so the run fails instead of wedging (rule 11).
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
