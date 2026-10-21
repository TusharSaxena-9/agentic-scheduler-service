package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Workflow contract for the monthly SP leave wallet accrual cron. */
@WorkflowInterface
public interface LeaveWalletAccrualWorkflow {

  /** Executes the monthly leave wallet accrual. */
  @WorkflowMethod
  void run();
}
