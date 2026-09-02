package com.yesmadam.schedulerservice.jobs.producthublivestock.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the product-hub live-stock alert cron.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments.
 */
@WorkflowInterface
public interface ProductHubLiveStockAlertWorkflow {

  /** Checks the configured hubs/products and alerts by mail and WhatsApp on anything flagged. */
  @WorkflowMethod
  void run();
}
