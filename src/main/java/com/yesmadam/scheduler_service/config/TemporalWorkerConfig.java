package com.yesmadam.scheduler_service.config;

import com.yesmadam.scheduler_service.booking.activity.BookingActivities;
import com.yesmadam.scheduler_service.booking.workflow.BookingCountWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class TemporalWorkerConfig {

    @Value("${temporal.taskqueue.booking}")
    private String bookingTaskQueue;

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        return WorkerFactory.newInstance(workflowClient);
    }

    @Bean
    public Worker bookingWorker(WorkerFactory factory, BookingActivities bookingActivities) {
        Worker worker = factory.newWorker(bookingTaskQueue);
        worker.registerWorkflowImplementationTypes(BookingCountWorkflowImpl.class);
        worker.registerActivitiesImplementations(bookingActivities);
        return worker;
    }
    // Starts all workers after all beans are registered
    @Bean
    public ApplicationRunner startWorkers(WorkerFactory factory) {
        return args -> {
            factory.start();
            log.info("All Temporal workers started");
        };
    }
}
