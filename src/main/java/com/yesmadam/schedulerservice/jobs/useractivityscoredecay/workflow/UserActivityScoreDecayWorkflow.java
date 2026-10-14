package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface UserActivityScoreDecayWorkflow {

  @WorkflowMethod
  void run();
}
