package com.yesmadam.schedulerservice.jobs.producthublivestock.workflow;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.producthublivestock.activity.ProductHubLiveStockAlertActivities;
import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertReport;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the product-hub live-stock alert workflow: check the configured hubs/products
 * right now, then mail and WhatsApp whatever was found.
 *
 * <p>Pure orchestration — all side effects (database, SMTP, WhatsApp) go through activities. Do not
 * inject Spring beans or use {@code @Value} here; workflow code must stay deterministic for Temporal
 * replay. Logging goes through {@link Workflow#getLogger} rather than a {@code @Slf4j} field: a
 * plain logger re-emits every line on replay.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class ProductHubLiveStockAlertWorkflowImpl implements ProductHubLiveStockAlertWorkflow {

  private static final Logger log = Workflow.getLogger(ProductHubLiveStockAlertWorkflowImpl.class);

  /**
   * The lookup is one join over a curated, bounded id list; 5 minutes is a generous backstop
   * against a bad plan, not a target — placeholder per CLAUDE.md's defaults table, unmeasured on
   * prod as of writing. Mail and WhatsApp get the "outbound send" shape (1m/5m/3 attempts) via a
   * per-method override — see {@code ProductHubLiveStockAlertActivitiesImpl} for why they stay on
   * the same {@code standard} queue as the fetch rather than being routed to {@code notification}.
   */
  private final ProductHubLiveStockAlertActivities activities =
      Workflow.newActivityStub(
          ProductHubLiveStockAlertActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "MailLiveStockAlertReport", sendActivityOptions(),
              "WhatsappLiveStockAlertReport", sendActivityOptions()));

  private static ActivityOptions sendActivityOptions() {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(1))
        .setScheduleToCloseTimeout(Duration.ofMinutes(5))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
        .build();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Mail and WhatsApp are attempted independently — a provider outage on one channel must not
   * suppress the other. If either exhausts its retries, the run fails (rather than swallowing the
   * outage) so it shows up in the Temporal UI and, paired with {@code pause-on-failure: true} on
   * this job's schedule, stops repeat firing until someone looks at it (CLAUDE.md rule 11).
   */
  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    LiveStockAlertReport report = activities.fetchLiveStockAlertReport();

    if (report.getRows() == null || report.getRows().isEmpty()) {
      log.info(
          "event=workflow_completed workflow_id={} flagged=0 detected_at={}",
          workflowId,
          report.getDetectedAt());
      return;
    }

    ActivityFailure mailFailure = null;
    ActivityFailure whatsappFailure = null;

    try {
      activities.mailLiveStockAlertReport(report);
    } catch (ActivityFailure e) {
      log.warn(
          "event=mail_send_failed workflow_id={} error={}", workflowId, rootCauseMessage(e));
      mailFailure = e;
    }

    try {
      //activities.whatsappLiveStockAlertReport(report);
    } catch (ActivityFailure e) {
      log.warn(
          "event=whatsapp_send_failed workflow_id={} error={}", workflowId, rootCauseMessage(e));
      whatsappFailure = e;
    }

    log.info(
        "event=workflow_completed workflow_id={} flagged={} detected_at={} mail_failed={} "
            + "whatsapp_failed={}",
        workflowId,
        report.getRows().size(),
        report.getDetectedAt(),
        mailFailure != null,
        whatsappFailure != null);

    if (mailFailure != null || whatsappFailure != null) {
      // The root cause (e.g. the actual Tata API rejection reason) is folded into the message
      // itself, not just a boolean flag, so it's visible directly in the Temporal UI without
      // having to correlate against worker pod logs.
      StringBuilder message = new StringBuilder("live stock alert send failed:");
      if (mailFailure != null) {
        message.append(" mail=").append(rootCauseMessage(mailFailure));
      }
      if (whatsappFailure != null) {
        message.append(" whatsapp=").append(rootCauseMessage(whatsappFailure));
      }
      throw ApplicationFailure.newFailureWithCause(
          message.toString(),
          "LiveStockAlertSendFailure",
          whatsappFailure != null ? whatsappFailure : mailFailure);
    }
  }

  private static String rootCauseMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();
  }
}
