package com.yesmadam.schedulerservice.jobs.ozuzuorder.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the Ozuzu (Shopify, via Unicommerce) order sync.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar starts
 * every job's workflow with no arguments. The window is derived inside the run.
 */
@WorkflowInterface
public interface OzuzuOrderSyncWorkflow {

  /** Syncs one trailing window of created/updated orders. */
  @WorkflowMethod
  void run();
}
