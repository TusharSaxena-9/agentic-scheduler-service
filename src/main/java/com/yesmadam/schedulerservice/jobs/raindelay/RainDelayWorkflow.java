package com.yesmadam.schedulerservice.jobs.raindelay;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** No-arg so it fits {@code CronScheduleRequest} — the registrar starts workflows with no args. */
@WorkflowInterface
public interface RainDelayWorkflow {

  @WorkflowMethod
  void run();
}
