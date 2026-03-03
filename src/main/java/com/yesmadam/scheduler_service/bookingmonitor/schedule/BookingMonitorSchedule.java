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
 * Registers the Temporal schedule for the booking monitor workflow. Runs every minute between 9AM
 * and 11:59PM IST.
 */
@Slf4j
@Configuration
public class BookingMonitorSchedule {

  @Value("${temporal.taskqueue.bookingmonitor}")
  private String taskQueue;

  @Value("${temporal.schedule.bookingmonitor}")
  private String scheduleId;

  @Value("${temporal.cron.bookingmonitor}")
  private String cronExpression;

  private final ScheduleClient scheduleClient;

  public BookingMonitorSchedule(ScheduleClient scheduleClient) {
    this.scheduleClient = scheduleClient;
  }

  /**
   * Creates the Temporal schedule for the booking monitor workflow. Skips creation if the schedule
   * already exists.
   */
  @PostConstruct
  @Loggable(operation = "create-booking-monitor-schedule")
  public void createBookingMonitorSchedule() {
    LoggingUtils.setCorrelationId(scheduleId);
    LoggingUtils.logAuditEvent(
        "SCHEDULE_CREATION_STARTED",
        scheduleId,
        String.format("cron=%s timezone=Asia/Kolkata", cronExpression));

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
          .pause();

      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATION_COMPLETED",
          scheduleId,
          "Booking monitor schedule created successfully");

    } catch (ScheduleAlreadyRunningException ex) {
      log.info("event=schedule_skipped schedule_id={} reason=already_exists", scheduleId);
      LoggingUtils.logAuditEvent(
          "SCHEDULE_ALREADY_EXISTS", scheduleId, "Schedule was already running, skipping creation");

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
