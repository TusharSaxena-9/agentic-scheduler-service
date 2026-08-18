package com.yesmadam.schedulerservice.jobs.passbookmismatch.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.crontiming.activity.CronTimingActivities;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.activity.PassbookMismatchActivities;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchReport;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the SP passbook mismatch audit workflow: audit the trailing window, then mail
 * whatever was found.
 *
 * <p>Pure orchestration — all side effects (database, SMTP) go through activities. Do not inject
 * Spring beans or use {@code @Value} here; workflow code must stay deterministic for Temporal
 * replay. The window is computed inside the fetch activity rather than here, so a replay re-audits
 * the original window instead of one that has slid forward. Logging goes through {@link
 * Workflow#getLogger} rather than a {@code @Slf4j} field: a plain logger re-emits every line on
 * replay.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.passbookmismatch}")
public class PassbookMismatchWorkflowImpl implements PassbookMismatchWorkflow {

  private static final Logger log = Workflow.getLogger(PassbookMismatchWorkflowImpl.class);

  /**
   * The audit query is one statement over a 30-minute window; ten minutes is a generous backstop
   * against a bad plan, not a target. Attempts are capped so a persistent failure fails the run
   * loudly instead of retrying forever and holding the workflow open past the next scheduled fire.
   */
  private final PassbookMismatchActivities passbookMismatchActivities =
      Workflow.newActivityStub(
          PassbookMismatchActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /** Cron timing bookkeeping, capped the same way. */
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
   * <p>Cron timing is attempted but never fatal — a broken cron-timing write shouldn't stop the
   * audit or the alert.
   */
  @Override
  @Loggable(operation = "passbook-mismatch-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    Long cronTimingId = saveCronStartQuietly();

    try {
      log.info("event=workflow_started workflow_id={}", workflowId);

      PassbookMismatchReport report = passbookMismatchActivities.fetchPassbookMismatchReport();

      // Separate activity, so an SES outage retries only the mail and never re-runs the query.
      passbookMismatchActivities.mailPassbookMismatchReport(report);

      log.info(
          "event=workflow_completed workflow_id={} window_start={} window_end={} mismatches={}",
          workflowId,
          report.getWindowStart(),
          report.getWindowEnd(),
          report.getRows().size());

    } finally {
      // Detached: a cancelled workflow cancels its pending activities, so a plain finally would be
      // cancelled too and the cron-end row would never be written.
      Workflow.newDetachedCancellationScope(() -> saveCronEndQuietly(cronTimingId)).run();
    }
  }

  /**
   * @return the cron timing id, or {@code null} if even 3 attempts couldn't record it.
   */
  private Long saveCronStartQuietly() {
    try {
      return cronTimingActivities.saveCronStart(
          "PassbookMismatchWorkflow", "run", "Audit SP passbook entries and mail mismatches");
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
      log.warn(
          "event=save_cron_end_failed cron_timing_id={} error={}", cronTimingId, e.getMessage());
    }
  }
}
