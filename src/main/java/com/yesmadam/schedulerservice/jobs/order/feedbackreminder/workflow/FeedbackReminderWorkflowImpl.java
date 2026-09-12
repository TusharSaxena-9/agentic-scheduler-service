package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.activity.FeedbackReminderActivities;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderFetchResult;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderStage;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the feedback-reminder cron job — for each of the 3h/24h/48h checkpoints,
 * independently fetch and send the WhatsApp nudge for that tick's tile of completed-unrated orders.
 *
 * <p>NOTE: pure orchestration only — no Spring injection or {@code @Value} here, since workflow code
 * must stay deterministic for replay, and logging goes through {@link Workflow#getLogger} so replay
 * doesn't re-emit every line.
 *
 * <p>NOTE: the three stages run independently so one's failure doesn't block the others, but the run
 * still fails at the end if any stage failed, so it stays visible in the Temporal UI (rule 11).
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class FeedbackReminderWorkflowImpl implements FeedbackReminderWorkflow {

  private static final Logger log = Workflow.getLogger(FeedbackReminderWorkflowImpl.class);

  /** The fetch query is one bounded read over a 5-minute tile — 2 minutes is a generous, unmeasured-on-prod placeholder, not a target. */
  private final FeedbackReminderActivities activities =
      Workflow.newActivityStub(
          FeedbackReminderActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(10))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of("SendFeedbackReminders", sendActivityOptions()));

  private static ActivityOptions sendActivityOptions() {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(2))
        .setScheduleToCloseTimeout(Duration.ofMinutes(3))
        // A retry here would re-send WhatsApp to every recipient the first attempt already reached, since there is no per-order sent marker (CLAUDE.md rule 14).
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
        .build();
  }

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    long nominalFireTimeMillis = Workflow.currentTimeMillis();
    log.info(
        "event=workflow_started workflow_id={} nominal_fire_time_millis={}",
        workflowId,
        nominalFireTimeMillis);

    int totalSent = 0;
    List<String> failedStages = new ArrayList<>();
    ActivityFailure lastFailure = null;

    for (FeedbackReminderStage stage : FeedbackReminderStage.values()) {
      try {
        FeedbackReminderFetchResult fetchResult =
            activities.fetchPendingFeedbackOrders(nominalFireTimeMillis, stage);
        totalSent += activities.sendFeedbackReminders(fetchResult);
      } catch (ActivityFailure e) {
        log.error(
            "event=feedback_reminder_stage_failed workflow_id={} stage={} error={}",
            workflowId,
            stage,
            rootCauseMessage(e));
        failedStages.add(stage.name());
        lastFailure = e;
      }
    }

    log.info(
        "event=workflow_completed workflow_id={} sent={} failed_stages={}",
        workflowId,
        totalSent,
        failedStages);

    if (!failedStages.isEmpty()) {
      throw ApplicationFailure.newFailureWithCause(
          "feedback reminder failed for stage(s): " + String.join(", ", failedStages),
          "FeedbackReminderStageFailure",
          lastFailure);
    }
  }

  private static String rootCauseMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();
  }
}
