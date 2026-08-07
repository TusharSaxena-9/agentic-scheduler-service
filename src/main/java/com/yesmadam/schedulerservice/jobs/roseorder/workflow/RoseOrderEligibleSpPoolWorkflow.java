package com.yesmadam.schedulerservice.jobs.roseorder.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Workflow contract for the rose order eligible SP pool update cron. */
@WorkflowInterface
public interface RoseOrderEligibleSpPoolWorkflow {

  /** Executes the eligible SP pool update workflow. */
  @WorkflowMethod
  void run();
}
