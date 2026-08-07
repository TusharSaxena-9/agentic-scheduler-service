package com.yesmadam.schedulerservice.common.temporal;

import java.util.Collections;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.logging.LoggingUtils;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import lombok.extern.slf4j.Slf4j;

/**
 * Registers cron-triggered Temporal schedules with the conventions shared by all jobs in this
 * service:
 *
 * <ul>
 *   <li>Created <b>paused</b> so they must be enabled manually from the Temporal UI
 *   <li>Idempotent — an already-existing schedule is left untouched
 *   <li>Audit events logged for creation, skip, and failure
 * </ul>
 *
 * <p>Job modules declare their schedule facts in a small {@code @Configuration} class and delegate
 * the registration mechanics here.
 */
@Slf4j
@Component
public class TemporalScheduleRegistrar {

  private final ScheduleClient scheduleClient;

  public TemporalScheduleRegistrar(ScheduleClient scheduleClient) {
    this.scheduleClient = scheduleClient;
  }

  /**
   * Creates the given cron schedule in a paused state, skipping creation if it already exists.
   *
   * @param request the schedule definition to register
   */
  public void registerPausedCronSchedule(CronScheduleRequest request) {
    String scheduleId = request.getScheduleId();
    LoggingUtils.setCorrelationId(scheduleId);
    LoggingUtils.logAuditEvent(
        "SCHEDULE_CREATE_STARTED",
        scheduleId,
        String.format(
            "cron=%s timezone=%s initial_state=paused",
            request.getCronExpression(), request.getTimeZone()));

    try {
      scheduleClient
          .createSchedule(scheduleId, buildSchedule(request), ScheduleOptions.newBuilder().build())
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
    }
  }

  private Schedule buildSchedule(CronScheduleRequest request) {
    return Schedule.newBuilder()
        .setSpec(
            ScheduleSpec.newBuilder()
                .setCronExpressions(Collections.singletonList(request.getCronExpression()))
                .setTimeZoneName(request.getTimeZone())
                .build())
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(request.getWorkflowType())
                .setOptions(
                    WorkflowOptions.newBuilder()
                        .setTaskQueue(request.getTaskQueue())
                        .setWorkflowId(request.getWorkflowId())
                        .build())
                .build())
        .build();
  }
}
