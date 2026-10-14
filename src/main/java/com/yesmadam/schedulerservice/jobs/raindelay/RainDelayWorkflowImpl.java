package com.yesmadam.schedulerservice.jobs.raindelay;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Orchestration only — no clock reads, no injection, no I/O. The window is computed in the first
 * activity and carried on its result. {@code Workflow.getLogger} so lines are not re-emitted on
 * replay.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class RainDelayWorkflowImpl implements RainDelayWorkflow {

  private static final Logger log = Workflow.getLogger(RainDelayWorkflowImpl.class);

  /**
   * Default covers the resolve step: one query plus a few ~1s HTTP lookups.
   *
   * <p>{@code NotifyRainyHubs} is pinned to <b>1 attempt</b>. There is no per-order sent marker any
   * more — cross-run duplicates are prevented by the windows not overlapping — so a retry would
   * re-send WhatsApp to every recipient the first attempt already reached (CLAUDE.md rule 14). Same
   * pin, for the same reason, as {@code FeedbackReminderWorkflowImpl}. One attempt means a mid-loop
   * failure loses the rest of that slot rather than duplicating the start of it, and with tiled
   * windows no later run will cover it; that is the deliberate cost of dropping the tracking table.
   *
   * <p>Neither activity heartbeats, so a cancelled workflow flips to CANCELED while the send loop
   * runs on. Bounded by one slot's targets.
   *
   * <p>All values are placeholders; replace once a real run is observed.
   */
  private final RainDelayActivities activities =
      Workflow.newActivityStub(
          RainDelayActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(8))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "NotifyRainyHubs",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(10))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(12))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build()));

  /**
   * Failures propagate: both activities wrap exhausted retries in {@code ActivityFailure}, which
   * fails the execution normally.
   *
   * <p>Not failures: a hub whose lookup failed, and a message the provider rejected. Both are
   * counted, neither triggers a retry that would walk the send loop again.
   */
  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    // The nominal fire time, not wall-clock: stable across replay, and the anchor the
    // non-overlapping window is measured from. A late start must not slide the tile.
    long nominalFireTimeMillis = Workflow.currentTimeMillis();
    log.info(
        "event=workflow_started workflow_id={} nominal_fire_time_millis={}",
        workflowId,
        nominalFireTimeMillis);

    RainyHubResolution resolution = activities.resolveRainyHubs(nominalFireTimeMillis);

    if (resolution.getConfiguredHubCount() == 0) {
      // Not configured is a state, not an error — failing here would trip pause-on-failure on a job
      // behaving exactly as specified.
      log.warn(
          "event=workflow_completed workflow_id={} reason=rain_hub_not_configured window={}..{}",
          workflowId,
          resolution.getWindowStart(),
          resolution.getWindowEnd());
      return;
    }

    if (resolution.getRainyHubIds().isEmpty()) {
      log.info(
          "event=workflow_completed workflow_id={} configured={} candidates={} rainy=0 unknown={}"
              + " window={}..{}",
          workflowId,
          resolution.getConfiguredHubCount(),
          resolution.getCandidateHubCount(),
          resolution.getUnknownHubCount(),
          resolution.getWindowStart(),
          resolution.getWindowEnd());
      return;
    }

    RainDelayNotifyResult result = activities.notifyRainyHubs(resolution);

    log.info(
        "event=workflow_completed workflow_id={} configured={} candidates={} rainy={} unknown={}"
            + " targets={} sent={} failed={} window={}..{}",
        workflowId,
        resolution.getConfiguredHubCount(),
        resolution.getCandidateHubCount(),
        resolution.getRainyHubIds().size(),
        resolution.getUnknownHubCount(),
        result.getTargets(),
        result.getSent(),
        result.getFailed(),
        resolution.getWindowStart(),
        resolution.getWindowEnd());
  }
}
