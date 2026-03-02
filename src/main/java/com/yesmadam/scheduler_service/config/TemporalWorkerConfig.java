package com.yesmadam.scheduler_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.scheduler_service.booking.activity.BookingActivities;
import com.yesmadam.scheduler_service.booking.workflow.BookingCountWorkflowImpl;
import com.yesmadam.scheduler_service.logging.LoggingUtils;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class TemporalWorkerConfig {

  @Value("${temporal.taskqueue.booking}")
  private String bookingTaskQueue;

  /**
   * Creates and configures the WorkerFactory bean.
   *
   * @param workflowClient the WorkflowClient dependency
   * @return a configured WorkerFactory instance
   */
  @Bean
  public WorkerFactory workerFactory(WorkflowClient workflowClient) {
    long startTime = LoggingUtils.logOperationStart("create-worker-factory");

    try {
      WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
      LoggingUtils.logOperationComplete("create-worker-factory", startTime);
      return factory;

    } catch (Exception ex) {
      LoggingUtils.logOperationFailure("create-worker-factory", startTime, ex);
      throw new RuntimeException("Failed to create WorkerFactory", ex);

    } finally {
      LoggingUtils.clearContext();
    }
  }

  /**
   * Creates and configures the booking worker. Registers workflow implementations and activity
   * implementations.
   *
   * @param factory the WorkerFactory dependency
   * @param bookingActivities the BookingActivities dependency
   * @return a configured Worker instance
   */
  @Bean
  public Worker bookingWorker(WorkerFactory factory, BookingActivities bookingActivities) {
    long startTime = LoggingUtils.logOperationStart("create-booking-worker");

    try {
      Worker worker = factory.newWorker(bookingTaskQueue);
      worker.registerWorkflowImplementationTypes(BookingCountWorkflowImpl.class);
      worker.registerActivitiesImplementations(bookingActivities);
      LoggingUtils.logOperationComplete("create-booking-worker", startTime);
      return worker;

    } catch (Exception ex) {
      LoggingUtils.logOperationFailure("create-booking-worker", startTime, ex);
      throw new RuntimeException(
          "Failed to create booking worker for task_queue=" + bookingTaskQueue, ex);

    } finally {
      LoggingUtils.clearContext();
    }
  }

  /**
   * Creates an ApplicationRunner to start all workers after bean initialization.
   *
   * @param factory the WorkerFactory dependency
   * @return an ApplicationRunner that starts the WorkerFactory
   */
  @Bean
  public ApplicationRunner startWorkers(WorkerFactory factory) {
    return args -> {
      long startTime = LoggingUtils.logOperationStart("start-temporal-workers");

      try {
        factory.start();
        LoggingUtils.logOperationComplete("start-temporal-workers", startTime);
        LoggingUtils.logAuditEvent(
            "WORKERS_STARTED", "TEMPORAL", "All Temporal workers started successfully");

      } catch (Exception ex) {
        LoggingUtils.logOperationFailure("start-temporal-workers", startTime, ex);
        LoggingUtils.logAuditEvent(
            "WORKERS_START_FAILED", "TEMPORAL", String.format("error=%s", ex.getMessage()));
        throw new RuntimeException("Failed to start Temporal workers", ex);

      } finally {
        LoggingUtils.clearContext();
      }
    };
  }
}
