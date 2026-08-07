package com.yesmadam.schedulerservice.jobs.sebaltagging.schedule;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest;
import com.yesmadam.schedulerservice.common.temporal.TemporalScheduleRegistrar;
import com.yesmadam.schedulerservice.jobs.sebaltagging.workflow.SebalTaggingWorkflow;

/**
 * Registers the Temporal schedule for the SEBAL sub-category tagging workflow. Created paused by
 * default; enable manually from the Temporal UI, after {@code roseordersppool} so eligibility for
 * the day is settled first.
 */
@Configuration
public class SebalTaggingSchedule {

  @Value("${temporal.taskqueue.sebaltagging}")
  private String taskQueue;

  @Value("${temporal.schedule.sebaltagging}")
  private String scheduleId;

  @Value("${temporal.cron.sebaltagging}")
  private String cronExpression;

  private final TemporalScheduleRegistrar scheduleRegistrar;

  public SebalTaggingSchedule(TemporalScheduleRegistrar scheduleRegistrar) {
    this.scheduleRegistrar = scheduleRegistrar;
  }

  @PostConstruct
  @Loggable(operation = "create-sebal-tagging-schedule")
  public void createSebalTaggingSchedule() {
    scheduleRegistrar.registerPausedCronSchedule(
        CronScheduleRequest.builder()
            .scheduleId(scheduleId)
            .cronExpression(cronExpression)
            .taskQueue(taskQueue)
            .workflowType(SebalTaggingWorkflow.class)
            .workflowId("sebal-tagging-workflow")
            .build());
  }
}
