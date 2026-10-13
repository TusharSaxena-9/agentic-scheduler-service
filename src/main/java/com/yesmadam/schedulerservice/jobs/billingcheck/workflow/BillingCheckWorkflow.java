package com.yesmadam.schedulerservice.jobs.billingcheck.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Daily at 17:00 IST: WhatsApps each customer whose Beauty / Beauty Salon at Home booking ended in
 * the previous 24 hours (17:00 to 17:00) their final bill, asking whether any service was not on it.
 */
@WorkflowInterface
public interface BillingCheckWorkflow {

  @WorkflowMethod
  void run();
}
