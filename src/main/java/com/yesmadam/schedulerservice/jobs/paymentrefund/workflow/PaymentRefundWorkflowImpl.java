package com.yesmadam.schedulerservice.jobs.paymentrefund.workflow;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
              // Outbound-send profile: up to three sequential gateway calls per row (PayU/Paytm
              // verify, OTP verify, create-order), each with a 45s read timeout (bumped from 15s
              // — create-order in particular is a heavy synchronous endpoint that can genuinely
              // take longer under load). startToClose covers the worst case of all three maxing
              // out (~135s) with margin.
              //
              // maximumAttempts is 2, not the usual 3: the idempotency checks inside the service
              // make a retry of one row safe for most branches, EXCEPT the PayU refund gap
              // documented on PaymentRefundOrderCreationService — a Temporal-level retry of this
              // activity is exactly what can trigger a double-refund-attempt if a crash happens
              // between a gateway call succeeding and the local write committing. Fewer retries
              // is the safer default here, not just a budget saving; in practice this retry
              // rarely fires at all, since processRow already converts ordinary failures into a
              // normal return value instead of throwing.
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(3))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(6))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    // Split with shortlink's own validateOrderCreationOnPayment cron by hour parity: that cron
    // only acts on even hours, this job only acts on odd hours, so the same payment_source_id is
    // never picked up by both at once. Read via Workflow.currentTimeMillis(), not
    // Instant.now()/System.currentTimeMillis(), so the check stays replay-safe (rule 1).
    int hour =
        Instant.ofEpochMilli(Workflow.currentTimeMillis())
            .atZone(ZoneId.of("Asia/Kolkata"))
            .getHour();
    if (hour % 2 == 0) {
      log.error("false error for testing event=workflow_skipped_even_hour_belongs_to_shortlink workflow_id={} hour={}", workflowId, hour);
      return;
    }

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
