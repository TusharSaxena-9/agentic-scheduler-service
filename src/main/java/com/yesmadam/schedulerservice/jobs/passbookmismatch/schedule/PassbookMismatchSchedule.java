package com.yesmadam.schedulerservice.jobs.passbookmismatch.schedule;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest;
import com.yesmadam.schedulerservice.common.temporal.TemporalScheduleRegistrar;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.workflow.PassbookMismatchWorkflow;

/**
 * Registers the Temporal schedule for the SP passbook mismatch audit. Runs every 30 minutes, all
 * day — passbook entries are written around the clock.
 *
 * <p>Created <b>paused</b>; enable manually from the Temporal UI. That matters more than usual
 * here: enabling it starts sending mail to real people.
 */
@Configuration
public class PassbookMismatchSchedule {

  @Value("${temporal.taskqueue.passbookmismatch}")
  private String taskQueue;

  @Value("${temporal.schedule.passbookmismatch}")
  private String scheduleId;

  @Value("${temporal.cron.passbookmismatch}")
  private String cronExpression;

  private final TemporalScheduleRegistrar scheduleRegistrar;

  public PassbookMismatchSchedule(TemporalScheduleRegistrar scheduleRegistrar) {
    this.scheduleRegistrar = scheduleRegistrar;
  }

  @PostConstruct
  @Loggable(operation = "create-passbook-mismatch-schedule")
  public void createPassbookMismatchSchedule() {
    scheduleRegistrar.registerPausedCronSchedule(
        CronScheduleRequest.builder()
            .scheduleId(scheduleId)
            .cronExpression(cronExpression)
            .taskQueue(taskQueue)
            .workflowType(PassbookMismatchWorkflow.class)
            .workflowId("passbook-mismatch-workflow")
            .build());
  }
}
