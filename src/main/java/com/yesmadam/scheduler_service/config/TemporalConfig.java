package com.yesmadam.scheduler_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.scheduler_service.logging.LoggingUtils;

import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class TemporalConfig {

  @Value("${temporal.server.target}")
  private String temporalTarget;

  /**
   * Creates and configures the WorkflowServiceStubs bean.
   *
   * @return a configured WorkflowServiceStubs instance
   */
  @Bean
  public WorkflowServiceStubs workflowServiceStubs() {
    long startTime = LoggingUtils.logOperationStart("create-workflow-service-stubs");

    try {
      WorkflowServiceStubs stubs =
          WorkflowServiceStubs.newServiceStubs(
              WorkflowServiceStubsOptions.newBuilder().setTarget(temporalTarget).build());
      LoggingUtils.logOperationComplete("create-workflow-service-stubs", startTime);
      return stubs;

    } catch (Exception ex) {
      LoggingUtils.logOperationFailure("create-workflow-service-stubs", startTime, ex);
      throw new RuntimeException(
          "Failed to create WorkflowServiceStubs for target=" + temporalTarget, ex);

    } finally {
      LoggingUtils.clearContext();
    }
  }

  /**
   * Creates and configures the WorkflowClient bean.
   *
   * @param stubs the WorkflowServiceStubs dependency
   * @return a configured WorkflowClient instance
   */
  @Bean
  public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
    long startTime = LoggingUtils.logOperationStart("create-workflow-client");

    try {
      WorkflowClient client = WorkflowClient.newInstance(stubs);
      LoggingUtils.logOperationComplete("create-workflow-client", startTime);
      return client;

    } catch (Exception ex) {
      LoggingUtils.logOperationFailure("create-workflow-client", startTime, ex);
      throw new RuntimeException("Failed to create WorkflowClient", ex);

    } finally {
      LoggingUtils.clearContext();
    }
  }

  /**
   * Creates and configures the ScheduleClient bean.
   *
   * @param stubs the WorkflowServiceStubs dependency
   * @return a configured ScheduleClient instance
   */
  @Bean
  public ScheduleClient scheduleClient(WorkflowServiceStubs stubs) {
    long startTime = LoggingUtils.logOperationStart("create-schedule-client");

    try {
      ScheduleClient client = ScheduleClient.newInstance(stubs);
      LoggingUtils.logOperationComplete("create-schedule-client", startTime);
      return client;

    } catch (Exception ex) {
      LoggingUtils.logOperationFailure("create-schedule-client", startTime, ex);
      throw new RuntimeException("Failed to create ScheduleClient", ex);

    } finally {
      LoggingUtils.clearContext();
    }
  }
}
