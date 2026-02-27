package com.yesmadam.scheduler_service.booking.schedule;

import com.yesmadam.scheduler_service.booking.workflow.BookingCountWorkflow;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public Object createBookingCountSchedule(ScheduleClient scheduleClient) {
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
            log.info("Schedule created: {}", scheduleId);
        } catch (ScheduleAlreadyRunningException e) {
            log.info("Schedule already exists: {}", scheduleId);
        } catch (Exception e) {
            log.warn("Failed to create schedule {}: {}", scheduleId, e.getMessage());
        }
        return null;
    }
}
