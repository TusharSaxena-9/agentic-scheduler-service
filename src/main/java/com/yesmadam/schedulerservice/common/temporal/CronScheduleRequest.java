package com.yesmadam.schedulerservice.common.temporal;

import lombok.Builder;
import lombok.Value;

/**
 * Everything needed to register a cron-triggered Temporal schedule.
 *
 * @see TemporalScheduleRegistrar
 */
@Value
@Builder
public class CronScheduleRequest {

  /** Unique Temporal schedule identifier. */
  String scheduleId;

  /** Cron expression that determines when the workflow fires. */
  String cronExpression;

  /** Task queue the workflow executes on. */
  String taskQueue;

  /** Workflow interface annotated with {@code @WorkflowInterface}. */
  Class<?> workflowType;

  /** Workflow ID assigned to each scheduled run. */
  String workflowId;

  /** IANA timezone the cron expression is evaluated in. */
  @Builder.Default String timeZone = "Asia/Kolkata";
}
