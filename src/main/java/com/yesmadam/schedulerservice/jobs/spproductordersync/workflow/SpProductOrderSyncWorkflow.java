package com.yesmadam.schedulerservice.jobs.spproductordersync.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Workflow contract for the SP delivered-unavailable-product sync job. */
@WorkflowInterface
public interface SpProductOrderSyncWorkflow {

  /** Executes the inventory-increment and availability-sync steps. */
  @WorkflowMethod
  void run();
}
