package com.yesmadam.scheduler_service.bookingmonitor.schedule;

import java.util.Collections;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.bookingmonitor.workflow.BookingMonitorWorkflow;
import com.yesmadam.scheduler_service.logging.LoggingUtils;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring configuration class that registers the Temporal schedule for the booking monitor workflow.
 *
 * <p>This class creates and configures a Temporal schedule that triggers the {@link
 * BookingMonitorWorkflow} according to the configured cron expression. The schedule is created on
 * application startup and is intentionally created in a <b>paused</b> state so it can be enabled
 * manually from the UI.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic schedule creation on application startup
 *   <li>Idempotent creation - skips if schedule already exists
 *   <li>Audit logging for schedule operations
 *   <li>Paused-by-default behavior for manual enablement
 * </ul>
 *
 * <p>Configuration properties:
 *
 * <ul>
 *   <li>{@code temporal.taskqueue.bookingmonitor} - Task queue for workflow execution
 *   <li>{@code temporal.schedule.bookingmonitor} - Unique schedule identifier
 *   <li>{@code temporal.cron.bookingmonitor} - Cron expression for schedule timing
 * </ul>
 */
@Slf4j
@Configuration
public class BookingMonitorSchedule {

  /** Temporal task queue for booking monitor workflow execution. */
  @Value("${temporal.taskqueue.bookingmonitor}")
  private String taskQueue;

  /** Unique Temporal schedule identifier. */
  @Value("${temporal.schedule.bookingmonitor}")
  private String scheduleId;

  /** Cron expression used by Temporal to determine the schedule. */
  @Value("${temporal.cron.bookingmonitor}")
  private String cronExpression;

  /** Temporal client used to create and manage schedules. */
  private final ScheduleClient scheduleClient;

  /**
   * Creates a new BookingMonitorSchedule with required dependencies.
   *
   * @param scheduleClient the Temporal schedule client for creating schedules
   */
  public BookingMonitorSchedule(ScheduleClient scheduleClient) {
    this.scheduleClient = scheduleClient;
  }

  /**
   * Creates the Temporal schedule for the booking monitor workflow.
   *
   * <p>This method is automatically called after Spring bean construction. It creates a schedule
   * that will trigger the {@link BookingMonitorWorkflow} according to the configured cron
   * expression.
   *
   * <p>The schedule is intentionally created in a paused state so that it can be enabled manually
   * from the UI.
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
  @Loggable(operation = "create-booking-monitor-schedule")
  public void createBookingMonitorSchedule() {
    LoggingUtils.setCorrelationId(scheduleId);
    LoggingUtils.logAuditEvent(
        "SCHEDULE_CREATE_STARTED",
        scheduleId,
        String.format("cron=%s timezone=Asia/Kolkata initial_state=paused", cronExpression));

    try {
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
                          .setWorkflowType(BookingMonitorWorkflow.class)
                          .setOptions(
                              WorkflowOptions.newBuilder()
                                  .setTaskQueue(taskQueue)
                                  .setWorkflowId("booking-monitor-workflow")
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
      log.info("event=schedule_skipped schedule_id={} reason=already_exists", scheduleId);
      LoggingUtils.logAuditEvent(
          "SCHEDULE_ALREADY_EXISTS", scheduleId, "Schedule already exists; skipping creation");

    } catch (RuntimeException ex) {
      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATION_FAILED", scheduleId, String.format("error=%s", ex.getMessage()));
      throw ex;

    } catch (Exception ex) {
      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATION_FAILED", scheduleId, String.format("error=%s", ex.getMessage()));
      throw new RuntimeException("Failed to create booking monitor schedule", ex);
    }
  }
}
