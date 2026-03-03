package com.yesmadam.scheduler_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.scheduler_service.bookingmonitor.activity.BookingMonitorActivities;
import com.yesmadam.scheduler_service.bookingmonitor.workflow.BookingMonitorWorkflowImpl;
import com.yesmadam.scheduler_service.crontiming.activity.CronTimingActivities;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import com.yesmadam.scheduler_service.packageexpiry.activity.PackageExpiryActivities;
import com.yesmadam.scheduler_service.packageexpiry.workflow.PackageExpiryWorkflowImpl;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class TemporalWorkerConfig {

  @Value("${temporal.taskqueue.booking}")
  private String bookingTaskQueue;

  @Value("${temporal.taskqueue.packageexpiry}")
  private String packageExpiryTaskQueue;

  @Value("${temporal.taskqueue.bookingmonitor}")
  private String bookingMonitorTaskQueue;

  @Bean
  public Worker packageExpiryWorker(
      WorkerFactory factory,
      PackageExpiryActivities packageExpiryActivities,
      CronTimingActivities cronTimingActivities) {
    Worker worker = factory.newWorker(packageExpiryTaskQueue);
    worker.registerWorkflowImplementationTypes(PackageExpiryWorkflowImpl.class);
    worker.registerActivitiesImplementations(packageExpiryActivities, cronTimingActivities);
    return worker;
  }

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

  @Bean
  public Worker bookingMonitorWorker(
      WorkerFactory factory,
      BookingMonitorActivities bookingMonitorActivities,
      CronTimingActivities cronTimingActivities) {
    Worker worker = factory.newWorker(bookingMonitorTaskQueue);
    worker.registerWorkflowImplementationTypes(BookingMonitorWorkflowImpl.class);
    worker.registerActivitiesImplementations(bookingMonitorActivities, cronTimingActivities);
    return worker;
  }
}
