package com.yesmadam.schedulerservice.jobs.commissionaudit.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the daily commission deduction audit.
 *
 * <p>Takes no arguments because the shared registrar starts every scheduled workflow with none. The
 * booking date is derived inside the fetch activity.
 */
@WorkflowInterface
public interface CommissionAuditWorkflow {

  /** Audits yesterday's completed orders and mails anything it finds. */
  @WorkflowMethod
  void run();
}
