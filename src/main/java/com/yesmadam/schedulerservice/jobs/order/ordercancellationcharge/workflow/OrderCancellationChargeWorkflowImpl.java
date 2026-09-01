package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.activity.OrderCancellationChargeActivities;
import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.model.OrderCancellationChargeExpiryResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the order cancellation charge expiry cron: one bulk update, one log line.
 *
 * <p>Pure orchestration — no Spring injection, no clock reads, no {@code @Loggable} on {@link
 * #run()} (CLAUDE.md rule 1). Logging goes through {@link Workflow#getLogger} rather than a {@code
 * @Slf4j} field, since a plain logger re-emits every line on replay. Deliberately does not write
 * {@code tbl_cron_time} — rule 8 says new jobs must not.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class OrderCancellationChargeWorkflowImpl implements OrderCancellationChargeWorkflow {

  private static final Logger log = Workflow.getLogger(OrderCancellationChargeWorkflowImpl.class);

  /**
   * Single bulk UPDATE over an indexed-but-small table (605 rows / 128 candidates measured on
   * UAT); 5 minutes is a generous backstop, not a target. Attempts are capped so a persistent
   * failure fails the run loudly instead of retrying forever and, under the default SKIP overlap
   * policy, silently suppressing every later tick.
   */
  private final OrderCancellationChargeActivities activities =
      Workflow.newActivityStub(
          OrderCancellationChargeActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    OrderCancellationChargeExpiryResult result = activities.expireOverdueCancellationCharges();

    log.info(
        "event=workflow_completed workflow_id={} cutoff={} updated_count={}",
        workflowId,
        result.getCutoff(),
        result.getUpdatedCount());
  }
}
