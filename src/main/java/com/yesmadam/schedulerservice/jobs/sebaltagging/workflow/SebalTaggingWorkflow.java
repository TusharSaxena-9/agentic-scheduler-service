package com.yesmadam.schedulerservice.jobs.sebaltagging.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the SEBAL sub-category tagging cron.
 *
 * <p>No-arg by design so it fits {@link com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest}
 * (the shared schedule registrar starts every job's workflow with no arguments). Full vs.
 * incremental is decided inside the run itself, from {@code SebalTaggingWatermark} — see
 * {@code SebalTaggingServiceImpl#fetchRunContext()}.
 */
@WorkflowInterface
public interface SebalTaggingWorkflow {

  /** Executes one SEBAL sub-category tagging run. */
  @WorkflowMethod
  void run();
}
