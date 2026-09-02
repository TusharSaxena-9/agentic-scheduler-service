package com.yesmadam.schedulerservice.jobs.autoJobDistribution.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.activity.AutoJobDistributionActivities;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPassResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Runs one advance pass per tick.
 *
 * <p>Pure orchestration — all database and HTTP work goes through the activity. Do not inject
 * Spring beans or use {@code @Value} here; workflow code must stay deterministic for Temporal
 * replay.
 *
 * <p><b>Retries are capped at one attempt on purpose.</b> Every other job here retries three times,
 * but a distribution pass is not safely repeatable: it closes rounds, writes audit rows, and fires
 * SP notifications through admin. A retry after a partial pass would re-evaluate rounds the
 * previous attempt already closed and could notify SPs twice. The pass is designed to be resumed by
 * the <em>next scheduled tick</em> rather than retried — anything it left live is simply picked up
 * five minutes later, which is exactly how the original cron recovered.
 *
 * <p>A Schedule's default overlap policy is SKIP, so two passes can never run concurrently even
 * across worker pods. That covers this schedule against itself; the cross-process case (the trigger
 * side, once it migrates as its own schedule) is handled by the DB lock inside the service.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class AutoJobDistributionWorkflowImpl implements AutoJobDistributionWorkflow {

  private static final Logger log = Workflow.getLogger(AutoJobDistributionWorkflowImpl.class);

  /**
   * Bounded by the one-active-distribution invariant: {@code findActiveDistributed()} selects
   * rounds with {@code is_active = 1 AND status = 'DISTRIBUTED'}, and the whole escalation model
   * exists to keep that at one. So a pass evaluates ~1 round, makes at most one chunked HTTP call
   * to admin (which answers 202 immediately) and at most one fire. Five minutes is a wide ceiling
   * on work that should take seconds, and it stops a wedged run from overlapping the next tick.
   *
   * <p><b>Heartbeat, because a wedge here is worse than a failure.</b> Each iteration can call
   * admin over HTTP. Without a heartbeat a hung socket holds a light slot and its database
   * connection for the full {@code startToClose}, and under SKIP overlap every tick that fires in
   * the meantime is dropped with no failed workflow and no alert. One minute of silence is well
   * past the 5s/10s client timeouts, so it only trips on a genuine stall.
   *
   * <p>{@code scheduleToClose} barely exceeds {@code startToClose} because there is only ever one
   * attempt — it exists so the bound is stated rather than absent (rule 4).
   */
  private final AutoJobDistributionActivities activities =
      Workflow.newActivityStub(
          AutoJobDistributionActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setHeartbeatTimeout(Duration.ofMinutes(1))
              .setScheduleToCloseTimeout(Duration.ofMinutes(6))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());

  /** {@inheritDoc} */
  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    log.info("event=workflow_started workflow_id={}", workflowId);

    DistributionPassResult result = activities.processActiveDistributions();

    log.info(
        "event=workflow_completed workflow_id={} active={} closed={} advanced={} fired_new={}",
        workflowId,
        result == null ? 0 : result.getActiveRounds(),
        result == null ? 0 : result.getClosedRounds(),
        result == null ? 0 : result.getAdvancedRounds(),
        result != null && result.isFiredNewDistribution());
  }
}
