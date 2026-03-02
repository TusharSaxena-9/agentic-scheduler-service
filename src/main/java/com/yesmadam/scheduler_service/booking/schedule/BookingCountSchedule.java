package com.yesmadam.scheduler_service.booking.schedule;

import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.booking.workflow.BookingCountWorkflow;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.Collections;

@Slf4j
@Configuration
public class BookingCountSchedule {

    @Value("${temporal.taskqueue.booking}")
    private String taskQueue;

    @Value("${temporal.schedule.booking}")
    private String scheduleId;

    @Value("${temporal.cron.booking}")
    private String cronExpression;

    private final ScheduleClient scheduleClient;

    public BookingCountSchedule(ScheduleClient scheduleClient) {
        this.scheduleClient = scheduleClient;
    }

    /**
     * Creates a Temporal schedule for booking count workflow execution.
     * Configures a cron-based schedule that triggers the booking count workflow.
     * ScheduleAlreadyRunningException is handled explicitly as it is not an error.
     */
    @PostConstruct
    @Loggable(operation = "create-booking-schedule")
    public void createBookingCountSchedule() {
        LoggingUtils.setCorrelationId(scheduleId);
        LoggingUtils.logAuditEvent("SCHEDULE_CREATION_STARTED", scheduleId,
                String.format("cron=%s timezone=Asia/Kolkata", cronExpression));

        try {
            scheduleClient.createSchedule(
                    scheduleId,
                    Schedule.newBuilder()
                            .setSpec(ScheduleSpec.newBuilder()
                                    .setCronExpressions(Collections.singletonList(cronExpression))
                                    .setTimeZoneName("Asia/Kolkata")
                                    .build())
                            .setAction(ScheduleActionStartWorkflow.newBuilder()
                                    .setWorkflowType(BookingCountWorkflow.class)
                                    .setOptions(WorkflowOptions.newBuilder()
                                            .setTaskQueue(taskQueue)
                                            .setWorkflowId("booking-count-workflow")
                                            .build())
                                    .build())
                            .build(),
                    ScheduleOptions.newBuilder().build()
            );

            LoggingUtils.logAuditEvent("SCHEDULE_CREATION_COMPLETED", scheduleId,
                    "Schedule created successfully");

        } catch (ScheduleAlreadyRunningException ex) {
            // Not an error - schedule already exists, skip creation
            log.info("event=schedule_skipped schedule_id={} reason=already_exists", scheduleId);
            LoggingUtils.logAuditEvent("SCHEDULE_ALREADY_EXISTS", scheduleId,
                    "Schedule was already running, skipping creation");

        } catch (RuntimeException ex) {
            LoggingUtils.logAuditEvent("SCHEDULE_CREATION_FAILED", scheduleId,
                    String.format("error=%s", ex.getMessage()));
            throw ex;

        } catch (Exception ex) {
            LoggingUtils.logAuditEvent("SCHEDULE_CREATION_FAILED", scheduleId,
                    String.format("error=%s", ex.getMessage()));
            throw new RuntimeException("Failed to create booking count schedule", ex);
        }
    }
}