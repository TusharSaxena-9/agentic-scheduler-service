package com.yesmadam.schedulerservice.jobs.packageexpiry.workflow;

import com.yesmadam.schedulerservice.jobs.packageexpiry.schedule.PackageExpirySchedule;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal workflow interface for the package expiry cron job.
 *
 * <p>This interface defines the workflow that orchestrates the complete package expiry process. The
 * workflow is responsible for:
 *
 * <ul>
 *   <li>Tracking cron execution timing for monitoring
 *   <li>Fetching active package sessions that need expiry checks
 *   <li>Retrieving service information for cost calculations
 *   <li>Expiring eligible package sessions in the database
 *   <li>Generating and saving financial reports
 * </ul>
 *
 * <p>The workflow follows Temporal's reliability patterns:
 *
 * <ul>
 *   <li>Durable execution - workflow state is persisted
 *   <li>Automatic retries on failures
 *   <li>Exactly-once execution guarantees
 *   <li>Activity timeouts and retry policies
 * </ul>
 *
 * <p>This workflow is scheduled to run daily at 12:10 AM IST via {@link PackageExpirySchedule}.
 *
 * @author Scheduler Service Team
 * @since 1.0.0
 */
@WorkflowInterface
public interface PackageExpiryWorkflow {

  /**
   * Executes the complete package expiry workflow.
   *
   * <p>This method orchestrates all the steps required to expire packages and generate the
   * necessary financial reports. The workflow includes:
   *
   * <ol>
   *   <li>Record cron start time for monitoring
   *   <li>Fetch all active package sessions from database
   *   <li>Retrieve service information for cost calculations
   *   <li>Build multi-session reports based on service costs
   *   <li>Mark packages as expired in database
   *   <li>Save financial reports for accounting
   *   <li>Record cron end time for monitoring
   * </ol>
   *
   * <p>The workflow handles empty result sets gracefully and ensures that cron timing is recorded
   * even if the workflow fails.
   */
  @WorkflowMethod
  void run();
}
