package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;


@WorkflowInterface
public interface SpTomorrowJobReminderWorkflow {

  @WorkflowMethod
  void run();
}
