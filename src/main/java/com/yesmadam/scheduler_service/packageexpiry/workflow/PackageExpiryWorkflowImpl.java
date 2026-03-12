package com.yesmadam.scheduler_service.packageexpiry.workflow;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.util.ObjectUtils;

import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.crontiming.activity.CronTimingActivities;
import com.yesmadam.scheduler_service.packageexpiry.activity.PackageExpiryActivities;
import com.yesmadam.scheduler_service.packageexpiry.model.MultiSessionReports;
import com.yesmadam.scheduler_service.packageexpiry.model.PackageSession;
import com.yesmadam.scheduler_service.packageexpiry.model.Services;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of the package expiry workflow for Temporal execution.
 *
 * <p>This workflow orchestrates the complete package expiry process including cron timing tracking,
 * package expiration, and financial report generation. The workflow is designed to be:
 *
 * <ul>
 *   <li>Durable - state is persisted and survives failures
 *   <li>Reliable - automatic retries on transient failures
 *   <li>Observable - comprehensive logging and monitoring
 *   <li>Idempotent - safe to retry without side effects
 * </ul>
 *
 * <p>Workflow execution flow:
 *
 * <ol>
 *   <li>Record cron start time for monitoring
 *   <li>Fetch all active package sessions from database
 *   <li>Extract service IDs from active packages
 *   <li>Fetch service information for cost calculations
 *   <li>Build multi-session financial reports
 *   <li>Mark packages as expired in database
 *   <li>Save financial reports for accounting
 *   <li>Record cron end time (always executed in finally block)
 * </ol>
 *
 * <p>Activity timeouts:
 *
 * <ul>
 *   <li>Package expiry activities: 10 minutes
 *   <li>Cron timing activities: 2 minutes
 * </ul>
 *
 * @author Scheduler Service Team
 * @since 1.0.0
 */
@Slf4j
public class PackageExpiryWorkflowImpl implements PackageExpiryWorkflow {

  /**
   * Activity stub for package expiry operations with 10-minute timeout. This handles the main
   * business logic of the workflow.
   */
  private final PackageExpiryActivities packageExpiryActivities =
      Workflow.newActivityStub(
          PackageExpiryActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(10)).build());

  /**
   * Activity stub for cron timing operations with 2-minute timeout. This handles tracking of
   * workflow execution timing for monitoring.
   */
  private final CronTimingActivities cronTimingActivities =
      Workflow.newActivityStub(
          CronTimingActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build());

  /**
   * {@inheritDoc}
   *
   * <p>Executes the complete package expiry workflow with proper error handling and monitoring. The
   * workflow ensures that cron timing is always recorded even if the main workflow logic fails.
   */
  @Override
  @Loggable(operation = "package-expiry-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    // Record workflow start time for monitoring
    Long cronTimingId =
        cronTimingActivities.saveCronStart(
            "PackageExpiryWorkflow", "run", "Execute every day 12:10 AM");

    try {
      log.info("event=workflow_started workflow_id={}", workflowId);

      // Step 1: Fetch active package sessions
      List<PackageSession> packageSessions = packageExpiryActivities.fetchActivePackageSessions();

      if (ObjectUtils.isEmpty(packageSessions)) {
        log.info("event=workflow_completed workflow_id={} reason=no_active_sessions", workflowId);
        return;
      }

      // Step 2: Extract service IDs for cost calculation
      List<Integer> serviceIds =
          packageSessions.stream().map(PackageSession::getServiceId).collect(Collectors.toList());

      // Step 3: Fetch service information and build reports
      Map<Integer, Services> servicesMap = packageExpiryActivities.fetchServices(serviceIds);
      List<MultiSessionReports> reports = buildMultiSessionReports(packageSessions, servicesMap);

      // Step 4: Expire package sessions
      packageExpiryActivities.expirePackageSessions(packageSessions);

      // Step 5: Save financial reports
      if (!ObjectUtils.isEmpty(reports)) {
        packageExpiryActivities.saveMultiSessionReports(reports);
      }

      log.info(
          "event=workflow_completed workflow_id={} sessions_expired={} reports_saved={}",
          workflowId,
          packageSessions.size(),
          reports.size());

    } finally {
      // Always record workflow end time, even on failure
      cronTimingActivities.saveCronEnd(cronTimingId);
    }
  }

  /**
   * Builds multi-session financial reports for expired packages.
   *
   * <p>Calculates the cost for each expired package based on the remaining sessions and service
   * pricing. The cost calculation includes:
   *
   * <pre>
   * cost = remaining_sessions * (pack_charge/sessions + convenience_fee/sessions)
   * </pre>
   *
   * @param packageSessions list of package sessions to generate reports for
   * @param servicesMap map of service ID to service information for pricing
   * @return list of multi-session reports, empty if no valid services found
   */
  private List<MultiSessionReports> buildMultiSessionReports(
      List<PackageSession> packageSessions, Map<Integer, Services> servicesMap) {

    List<MultiSessionReports> reports = new ArrayList<>();
    if (ObjectUtils.isEmpty(servicesMap)) return reports;

    packageSessions.forEach(
        ps -> {
          Services service = servicesMap.get(ps.getServiceId());
          if (ObjectUtils.isEmpty(service)) return;

          // Calculate cost based on remaining sessions and service pricing
          Integer servCost =
              ps.getRemainingSessionCount()
                  * (service.getPackCharge() / service.getNoOfSessions()
                      + service.getConvFee() / service.getNoOfSessions());

          // Create financial report
          MultiSessionReports report = new MultiSessionReports();
          report.setCostHead("Pack Expiry");
          report.setAmount(servCost);
          report.setTransactionType("Net off at EOD internal");
          report.setUserId(ps.getUserId());
          report.setPackageId(ps.getId());
          report.setCreatedAt(new Timestamp(System.currentTimeMillis()));
          reports.add(report);
        });

    return reports;
  }
}
