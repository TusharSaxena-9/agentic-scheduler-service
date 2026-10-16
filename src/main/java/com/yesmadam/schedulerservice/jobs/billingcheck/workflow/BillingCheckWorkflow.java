package com.yesmadam.schedulerservice.jobs.billingcheck.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Daily at 17:00 IST: WhatsApps each customer of the previous 17:00-17:00 their final bill. */
@WorkflowInterface
public interface BillingCheckWorkflow {

  @WorkflowMethod
  void run();
}
