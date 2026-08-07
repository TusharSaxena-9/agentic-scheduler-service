package com.yesmadam.schedulerservice.jobs.roseorder.schedule;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest;
import com.yesmadam.schedulerservice.common.temporal.TemporalScheduleRegistrar;
import com.yesmadam.schedulerservice.jobs.roseorder.workflow.RoseOrderEligibleSpPoolWorkflow;

/**
 * Registers the Temporal schedule for the rose order eligible SP pool update workflow. Created
 * paused by default; enable manually from the Temporal UI.
 */
@Configuration
public class RoseOrderEligibleSpPoolSchedule {

  @Value("${temporal.taskqueue.roseorder}")
  private String taskQueue;

  @Value("${temporal.schedule.roseordersppool}")
  private String scheduleId;

  @Value("${temporal.cron.roseordersppool}")
  private String cronExpression;

  private final TemporalScheduleRegistrar scheduleRegistrar;

  public RoseOrderEligibleSpPoolSchedule(TemporalScheduleRegistrar scheduleRegistrar) {
    this.scheduleRegistrar = scheduleRegistrar;
  }

  @PostConstruct
  @Loggable(operation = "create-rose-order-eligible-sp-pool-schedule")
  public void createRoseOrderEligibleSpPoolSchedule() {
    scheduleRegistrar.registerPausedCronSchedule(
        CronScheduleRequest.builder()
            .scheduleId(scheduleId)
            .cronExpression(cronExpression)
            .taskQueue(taskQueue)
            .workflowType(RoseOrderEligibleSpPoolWorkflow.class)
            .workflowId("rose-order-eligible-sp-pool-workflow")
            .build());
  }
}
