package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.workflow;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.activity.UserActivityScoreDecayActivities;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayChunkResult;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayMultiplierCurve;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class UserActivityScoreDecayWorkflowImpl implements UserActivityScoreDecayWorkflow {

  private static final Logger log = Workflow.getLogger(UserActivityScoreDecayWorkflowImpl.class);

  private static final int BATCH_SIZE = 10_000;

  private final UserActivityScoreDecayActivities activities =
      Workflow.newActivityStub(
          UserActivityScoreDecayActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(30))
              .setScheduleToCloseTimeout(Duration.ofMinutes(40))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "FetchDecayMultiplierCurve",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(30))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(3))
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    DecayMultiplierCurve curve = activities.fetchDecayMultiplierCurve();

    long totalDecayed = 0;
    int decayRowsUpdated;
    do {
      DecayChunkResult decayResult = activities.decayDueBatch(curve, BATCH_SIZE);
      decayRowsUpdated = decayResult.getRowsUpdated();
      totalDecayed += decayRowsUpdated;
    } while (decayRowsUpdated == BATCH_SIZE);

    long totalSuppressionsExpired = 0;
    int suppressionRowsUpdated;
    do {
      DecayChunkResult suppressionResult = activities.creditExpiredSuppressions(BATCH_SIZE);
      suppressionRowsUpdated = suppressionResult.getRowsUpdated();
      totalSuppressionsExpired += suppressionRowsUpdated;
    } while (suppressionRowsUpdated == BATCH_SIZE);

    log.info(
        "event=workflow_completed workflow_id={} total_rows_decayed={} total_suppressions_expired={}",
        workflowId,
        totalDecayed,
        totalSuppressionsExpired);
  }
}
