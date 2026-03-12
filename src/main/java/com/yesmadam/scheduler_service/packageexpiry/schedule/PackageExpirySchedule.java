package com.yesmadam.scheduler_service.packageexpiry.schedule;

import java.util.Collections;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import com.yesmadam.scheduler_service.packageexpiry.workflow.PackageExpiryWorkflow;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring configuration class that registers the Temporal schedule for package expiry workflow.
 *
 * <p>This class creates and configures a Temporal schedule for the package expiry workflow. The
 * schedule is created on application startup and is intentionally created in a <b>paused</b> state
 * so it can be enabled manually from the UI.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic schedule creation on application startup
 *   <li>Idempotent creation - skips if schedule already exists
 *   <li>Comprehensive audit logging for schedule operations
 *   <li>Proper error handling and reporting
 *   <li>Paused-by-default behavior for manual enablement
 *   <li>Configurable cron expression and task queue
 * </ul>
 *
 * <p>Configuration properties:
 *
 * <ul>
 *   <li>{@code temporal.taskqueue.packageexpiry} - Task queue for workflow execution
 *   <li>{@code temporal.schedule.packageexpiry} - Unique schedule identifier
 *   <li>{@code temporal.cron.packageexpiry} - Cron expression for schedule timing
 * </ul>
 *
 * <p>The schedule is created automatically when the Spring application starts via the
 * {@code @PostConstruct} annotation.
 *
 * @author Scheduler Service Team
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class PackageExpirySchedule {

  @Value("${temporal.taskqueue.packageexpiry}")
  private String taskQueue;

  @Value("${temporal.schedule.packageexpiry}")
  private String scheduleId;

  @Value("${temporal.cron.packageexpiry}")
  private String cronExpression;

  private final ScheduleClient scheduleClient;

  /**
   * Creates a new PackageExpirySchedule with required dependencies.
   *
   * @param scheduleClient the Temporal schedule client for creating schedules
   */
  public PackageExpirySchedule(ScheduleClient scheduleClient) {
    this.scheduleClient = scheduleClient;
  }

  /**
   * Creates the Temporal schedule for the package expiry workflow.
   *
   * <p>This method is automatically called after Spring bean construction. It creates a schedule
   * that will trigger the {@link PackageExpiryWorkflow} according to the configured cron
   * expression.
   *
   * <p>The schedule is intentionally created in a paused state so that it can be enabled manually
   * from the UI.
   *
   * <p>Schedule configuration:
   *
   * <ul>
   *   <li>Cron expression: {@code temporal.cron.packageexpiry} property
   *   <li>Timezone: Asia/Kolkata (IST)
   *   <li>Workflow ID: package-expiry-workflow
   *   <li>Task queue: {@code temporal.taskqueue.packageexpiry} property
   * </ul>
   *
   * <p>Error handling:
   *
   * <ul>
   *   <li>If schedule already exists - logs info and continues
   *   <li>If creation fails - logs error and rethrows exception
   *   <li>All operations are logged for audit purposes
   * </ul>
   */
  @PostConstruct
  @Loggable(operation = "create-package-expiry-schedule")
  public void createPackageExpirySchedule() {
    LoggingUtils.setCorrelationId(scheduleId);
    LoggingUtils.logAuditEvent(
        "SCHEDULE_CREATE_STARTED",
        scheduleId,
        String.format("cron=%s timezone=Asia/Kolkata initial_state=paused", cronExpression));

    try {
      // Create the Temporal schedule with specified configuration
      scheduleClient
          .createSchedule(
              scheduleId,
              Schedule.newBuilder()
                  .setSpec(
                      ScheduleSpec.newBuilder()
                          .setCronExpressions(Collections.singletonList(cronExpression))
                          .setTimeZoneName("Asia/Kolkata")
                          .build())
                  .setAction(
                      ScheduleActionStartWorkflow.newBuilder()
                          .setWorkflowType(PackageExpiryWorkflow.class)
                          .setOptions(
                              WorkflowOptions.newBuilder()
                                  .setTaskQueue(taskQueue)
                                  .setWorkflowId("package-expiry-workflow")
                                  .build())
                          .build())
                  .build(),
              ScheduleOptions.newBuilder().build())
          .pause("Created paused by default; enable manually via UI");
      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATED_PAUSED",
          scheduleId,
          "Schedule created and paused intentionally (enable manually via UI)");

    } catch (ScheduleAlreadyRunningException ex) {
      // Schedule already exists - this is expected on restarts
      log.info("event=schedule_skipped schedule_id={} reason=already_exists", scheduleId);
      LoggingUtils.logAuditEvent(
          "SCHEDULE_ALREADY_EXISTS", scheduleId, "Schedule already exists; skipping creation");

    } catch (RuntimeException ex) {
      // Runtime exceptions - log and rethrow
      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATION_FAILED", scheduleId, String.format("error=%s", ex.getMessage()));
      throw ex;

    } catch (Exception ex) {
      // Other exceptions - wrap in runtime exception
      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATION_FAILED", scheduleId, String.format("error=%s", ex.getMessage()));
      throw new RuntimeException("Failed to create package expiry schedule", ex);
    }
  }
}
