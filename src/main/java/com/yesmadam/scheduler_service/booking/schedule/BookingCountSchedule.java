package com.yesmadam.scheduler_service.booking.schedule;

import com.yesmadam.scheduler_service.booking.workflow.BookingCountWorkflow;
import com.yesmadam.scheduler_service.exception.handler.GlobalExceptionHandler;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    
    private final GlobalExceptionHandler exceptionHandler;
    private final ScheduleClient scheduleClient;
    
    public BookingCountSchedule(@Autowired GlobalExceptionHandler exceptionHandler, 
                               @Autowired ScheduleClient scheduleClient) {
        this.exceptionHandler = exceptionHandler;
        this.scheduleClient = scheduleClient;
    }

    /**
     * Creates a Temporal schedule for booking count workflow execution.
     * Configures a cron-based schedule that triggers the booking count workflow.
     */
    @PostConstruct
    public void createBookingCountSchedule() {
        String correlationId = LoggingUtils.setCorrelationId(null);
        long startTime = LoggingUtils.logOperationStart("create-booking-schedule");
        
        try {
            LoggingUtils.logAuditEvent("SCHEDULE_CREATION_STARTED", scheduleId, 
                String.format("Creating schedule with cron: %s, timezone: Asia/Kolkata", cronExpression));
            
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
            
            LoggingUtils.logOperationComplete("create-booking-schedule", startTime);
            log.info("Schedule created successfully: {}", scheduleId);
            LoggingUtils.logAuditEvent("SCHEDULE_CREATION_COMPLETED", scheduleId, 
                "Schedule created successfully");
            
        } catch (ScheduleAlreadyRunningException e) {
            LoggingUtils.logOperationComplete("create-booking-schedule", startTime);
            log.info("Schedule already exists: {}", scheduleId);
            LoggingUtils.logAuditEvent("SCHEDULE_ALREADY_EXISTS", scheduleId, 
                "Schedule was already running");
            
        } catch (RuntimeException ex) {
            exceptionHandler.handleRuntimeException(ex);
            throw ex;
        } catch (Exception ex) {
            exceptionHandler.handleGenericException(ex);
            LoggingUtils.logAuditEvent("SCHEDULE_CREATION_FAILED", scheduleId, 
                String.format("Schedule creation failed: %s", ex.getMessage()));
            
        } finally {
            LoggingUtils.clearContext();
        }
    }
}
