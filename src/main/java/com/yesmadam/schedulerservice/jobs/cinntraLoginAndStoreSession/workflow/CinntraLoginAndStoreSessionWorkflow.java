package com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the Cinntra login/session-refresh cron. Runs every 30 minutes — mirrors
 * tracking's {@code CintraSession.loginAndStoreSession}.
 *
 * <p>No-arg by design so it fits {@link
 * com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest} — the shared registrar
 * starts every job's workflow with no arguments.
 */
@WorkflowInterface
public interface CinntraLoginAndStoreSessionWorkflow {

  /** Runs the Cinntra login/session-refresh. */
  @WorkflowMethod
  void run();
}
