package com.yesmadam.schedulerservice.jobs.spNotReachedHome.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Tracks SPs who finished their last job of the day after 20:00 and have not returned home. Runs
 * every 5 minutes between 20:00 and 23:59 IST — mirrors cron's {@code
 * SpNotReachedHomeConstants.CRON_EXPRESSION} (SAAB-90).
 */
@WorkflowInterface
public interface SpNotReachedHomeWorkflow {

  @WorkflowMethod
  void run();
}
