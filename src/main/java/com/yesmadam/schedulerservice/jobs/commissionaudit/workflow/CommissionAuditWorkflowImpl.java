package com.yesmadam.schedulerservice.jobs.commissionaudit.workflow;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.commissionaudit.activity.CommissionAuditActivities;
import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditReport;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the commission deduction audit: audit yesterday's completed orders, then mail
 * whatever was found.
 *
 * <p>Orchestration only. The database and SMTP work happens in activities. The booking date is
 * computed inside the fetch activity, so a replay re-audits the original date and not a later one.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class CommissionAuditWorkflowImpl implements CommissionAuditWorkflow {

  private static final Logger log = Workflow.getLogger(CommissionAuditWorkflowImpl.class);

  /**
   * The fetch runs a single single-day query over a bounded set. These values are placeholders from
   * CLAUDE.md's defaults table and have not been measured in production. The mail uses the
   * outbound-send shape (1m/5m) and inherits the 3 attempts from the default.
   */
  private final CommissionAuditActivities activities =
      Workflow.newActivityStub(
          CommissionAuditActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              // Read-only fetch, so a retry is safe. One transient failure must not fail the run
              // and trip pause-on-failure.
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "MailCommissionAuditReport",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                  .build()));

  /** {@inheritDoc} */
  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    CommissionAuditReport report = activities.fetchCommissionAuditReport();

    // Separate activity, so an SES outage retries only the mail and never re-runs the query.
    activities.mailCommissionAuditReport(report);

    log.info(
        "event=workflow_completed workflow_id={} booking_date={} flagged={}",
        workflowId,
        report.getBookingDate(),
        report.getRows() == null ? 0 : report.getRows().size());
  }
}
