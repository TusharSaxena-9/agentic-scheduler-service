package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.activity.SpTomorrowJobReminderActivities;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.model.SpTomorrowJobReminderBatch;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;


@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class SpTomorrowJobReminderWorkflowImpl implements SpTomorrowJobReminderWorkflow {

  private static final Logger log =
      Workflow.getLogger(SpTomorrowJobReminderWorkflowImpl.class);


  private final SpTomorrowJobReminderActivities activities =
      Workflow.newActivityStub(
          SpTomorrowJobReminderActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              .setScheduleToCloseTimeout(Duration.ofMinutes(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  @Loggable(operation = "sp-tomorrow-job-reminder-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    SpTomorrowJobReminderBatch batch = activities.fetchTomorrowReminders();

    if (batch.getSpIds() == null || batch.getSpIds().isEmpty()) {
      log.info(
          "event=workflow_completed workflow_id={} job_date={} sp_count=0",
          workflowId,
          batch.getJobDate());
      return;
    }

    activities.sendTomorrowReminders(batch);

    log.info(
        "event=workflow_completed workflow_id={} job_date={} sp_count={}",
        workflowId,
        batch.getJobDate(),
        batch.getSpIds().size());
  }
}
