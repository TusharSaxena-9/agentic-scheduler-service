package com.yesmadam.schedulerservice.jobs.paymentrefund.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.paymentrefund.activity.PaymentRefundActivities;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundRowResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Pure orchestration — no Spring injection, no clock reads, no {@code @Loggable} on {@link #run()}
 * (CLAUDE.md rule 1). One activity call per checksum row, not one activity for the whole batch:
 * volume here is naturally small (a handful of payment/order mismatches per 3-minute window, not
 * thousands), so the chunk-per-activity boundary from rule 14 is sized to "one payment" rather than
 * "one page of rows" — a retry or failure on one problem row never re-touches rows that already
 * succeeded. Deliberately does not write {@code tbl_cron_time} (rule 8).
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class PaymentRefundWorkflowImpl implements PaymentRefundWorkflow {

  private static final Logger log = Workflow.getLogger(PaymentRefundWorkflowImpl.class);

  private final PaymentRefundActivities activities =
      Workflow.newActivityStub(
          PaymentRefundActivities.class,
          // Default profile: an indexed lookup / small write.
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setScheduleToCloseTimeout(Duration.ofMinutes(3))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "ProcessChecksumRow",
              // Outbound-send profile: Paytm/PayU/app-server HTTP calls per row. The idempotency
              // checks inside the service make a retry of one row safe, EXCEPT the PayU refund gap
              // documented on PaymentRefundOrderCreationService.
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    List<Long> checksumIds = activities.findPendingChecksumIds();

    int created = 0;
    int refunded = 0;
    int alreadyHandled = 0;
    int notYet = 0;
    int unsupported = 0;
    int failed = 0;

    for (Long checksumId : checksumIds) {
      PaymentRefundRowResult result = activities.processChecksumRow(checksumId);
      switch (result.getOutcome()) {
        case ORDER_CREATED -> created++;
        case REFUND_ISSUED -> refunded++;
        case ALREADY_HANDLED -> alreadyHandled++;
        case NOT_SUCCESSFUL_YET -> notYet++;
        case UNSUPPORTED_PAYING_FOR -> unsupported++;
        case FAILED -> failed++;
      }
    }

    log.info(
        "event=workflow_completed workflow_id={} candidates={} created={} refunded={} "
            + "already_handled={} not_yet_successful={} unsupported={} failed={}",
        workflowId,
        checksumIds.size(),
        created,
        refunded,
        alreadyHandled,
        notYet,
        unsupported,
        failed);
  }
}
