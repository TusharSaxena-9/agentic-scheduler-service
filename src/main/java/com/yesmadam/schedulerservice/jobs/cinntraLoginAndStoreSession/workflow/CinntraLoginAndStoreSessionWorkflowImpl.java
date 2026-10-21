package com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.activity.CinntraLoginAndStoreSessionActivities;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the Cinntra login/session-refresh workflow.
 *
 * <p>Mirrors tracking's {@code loginAndStoreSession} exactly. See {@code
 * CinntraLoginAndStoreSessionService}'s class Javadoc for the other (logging-only) deviations.
 *
 * <p>Pure orchestration — the actual HTTP call and upsert go through the activity. Do not inject
 * Spring beans or use {@code @Value} here; workflow code must stay deterministic for Temporal
 * replay. Logging goes through {@link Workflow#getLogger} rather than a {@code @Slf4j} field: a
 * plain logger re-emits every line on replay.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class CinntraLoginAndStoreSessionWorkflowImpl implements CinntraLoginAndStoreSessionWorkflow {

  private static final Logger log = Workflow.getLogger(CinntraLoginAndStoreSessionWorkflowImpl.class);

  private final CinntraLoginAndStoreSessionActivities activities =
      Workflow.newActivityStub(
          CinntraLoginAndStoreSessionActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(8))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /** {@inheritDoc} */
  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    try {
      activities.loginAndStoreSession();
      log.info("event=cinntra_login_and_store_session_completed workflow_id={}", workflowId);
    } catch (Exception ex) {
      log.error("event=cinntra_login_and_store_session_failed workflow_id={} error={}", workflowId, ex.getMessage(), ex);
    }

    log.info("event=workflow_completed workflow_id={}", workflowId);
  }
}
