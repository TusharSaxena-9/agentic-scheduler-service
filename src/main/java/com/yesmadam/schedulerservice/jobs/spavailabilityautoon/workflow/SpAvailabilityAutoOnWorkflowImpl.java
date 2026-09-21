package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.workflow;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.activity.SpAvailabilityAutoOnActivities;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Auto SP-availability-on: once a day, every active SP whose <b>first</b> kit assignment was
 * approved on D-2 (the day before yesterday, Asia/Kolkata) and who is still marked unavailable gets
 * availability turned on, a row on their own change timeline recording why, and a CleverTap push
 * telling them.
 *
 * <p>Two deliberate narrowings live in the eligibility query rather than here, and both change who
 * is in scope: it is the <em>first</em> assignment per SP, so a replacement kit does not re-enable
 * anyone; and the window is D-2 rather than D-1, giving an approval a full day to settle. See
 * {@code ProductKitApprovalRepository}.
 *
 * <p>This job sends nothing. The heads-up went out at 20:00 from {@link
 * SpKitApprovedNotifyWorkflowImpl}, on the same cohort — both resolve the same window (yesterday,
 * whole day, IST) on the same date, so an SP is told at 20:00 and live at 23:30 the same night.
 *
 * <p><b>The two are coupled by the window only, not by state.</b> This job does not read what the
 * 20:00 run claimed or sent. If that run failed entirely, this one still switches those SPs on —
 * they simply never got a heads-up. That is the right failure direction: being live is the outcome
 * that matters, the push is a courtesy. The converse also holds — an SP notified at 20:00 whom an
 * operator switches on manually at 21:00 is skipped here by the {@code availability} guard, with no
 * second history row.
 *
 * <p>Resolve the window once, then loop: flip a page, advance the cursor. The loop lives here
 * rather than inside one activity so the cursor is durable — a worker restart resumes from history
 * rather than losing its place — and so a failure costs one batch rather than the run.
 *
 * <p>Pure orchestration otherwise — no Spring injection, no clock reads, no {@code @Loggable} on
 * {@link #run()} (rule 1). Logging goes through {@link Workflow#getLogger} rather than a
 * {@code @Slf4j} field, since a plain logger re-emits every line on every replay (rule 2).
 * Deliberately does not write {@code tbl_cron_time} — rule 8 says new jobs must not.
 *
 * <p><b>Known consequence of the rule as specified:</b> eligibility is "first kit approved on D-2
 * and availability is currently off", with no memory of how it got that way. So an SP whose first
 * kit was approved on D-2 and whom an operator then deliberately switched off is switched back on
 * by tonight's run — and told so by CleverTap. That is one night only (tomorrow's window no longer
 * contains their approval), and the history row identifies the job as the actor. If a deliberate
 * off needs to survive that night, the fix is to consult the SP's latest {@code USER_AVAILABILITY}
 * history row and skip SPs turned off after their approval; that is a deliberate extension of the
 * rule, not a bug fix, so it is written down here rather than assumed.
 *
 * <p>The arithmetic below (counter increments, a null-safe map read) is the only logic in workflow
 * code, and it cannot throw. That matters while {@code failWorkflowExceptionTypes} is still unset
 * service-wide (rule 11): an exception thrown here would wedge the run as an endlessly-retrying
 * workflow task rather than fail it, invisibly, and under SKIP that would suppress every later
 * tick. Anything added here that can throw belongs in an activity instead.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpAvailabilityAutoOnWorkflowImpl implements SpAvailabilityAutoOnWorkflow {

  private static final Logger log = Workflow.getLogger(SpAvailabilityAutoOnWorkflowImpl.class);

  /**
   * Hard stop on the batch loop. Nothing should approach it — this is one day of kit approvals,
   * expected in the tens against a batch size of 500 — so it is a guarantee of termination rather
   * than a capacity setting: without it, an activity that somehow returned a non-advancing cursor
   * would spin, filling history. Reaching it is logged as {@code truncated=true}, which is the
   * signal that the assumption above is wrong and the cap (or the batch size) needs raising.
   *
   * <p>A literal rather than a property because workflow code cannot read configuration (rule 1),
   * and because changing it changes the command sequence of in-flight runs.
   */
  private static final int MAX_BATCHES = 200;

  /**
   * Window resolution and the per-batch flip take the "query or batch write over a bounded set"
   * shape from rule 4's defaults table — the bounded set being one page of one calendar day's kit
   * approvals. <b>Placeholder</b>: no production run has been observed, and the eligibility query
   * is currently an unindexed scan of {@code tbl_product_kit_assign_to_sp} (see {@code
   * ProductKitApprovalRepository}), which is what the 5-minute per-attempt bound is really
   * absorbing. Replace these once a real run is measured, and revisit when that index lands.
   *
   * <p>No {@code heartbeatTimeout} anywhere: no single attempt is expected to approach a minute,
   * and a heartbeat timeout without matching {@code heartbeat(...)} calls fails the activity every
   * time at that interval (rule 4). If a measured duration ever crosses a minute the fix is both —
   * the timeout and per-page heartbeats — not the timeout alone.
   *
   * <p>Three attempts is safe on the flip specifically because that write converges: both the
   * re-check and the {@code UPDATE} carry an {@code availability is null or false} guard, so an
   * attempt that commits and then dies before Temporal records completion is re-run against rows
   * that no longer match, flipping nothing and writing no second history row (rule 14). The send is
   * the opposite — CleverTap has no dedup key, so a retry after a lost response double-delivers.
   * Three attempts is still right there, because the realistic failure is a transport error where
   * nothing was delivered; a duplicate push is a far cheaper mistake than a silently dropped one.
   */
  private final SpAvailabilityAutoOnActivities activities =
      Workflow.newActivityStub(
          SpAvailabilityAutoOnActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    SpAvailabilityAutoOnWindow window = activities.resolveRunWindow();

    int cursor = 0;
    int candidateCount = 0;
    int turnedOnCount = 0;
    int batchCount = 0;
    boolean truncated = false;
    long findEligibleMs = 0;
    long turnOnMs = 0;

    while (batchCount < MAX_BATCHES) {
      SpKitCohortBatch batch = activities.flipNextBatch(window, cursor);

      findEligibleMs += durationOf(batch, "findEligibleSpIds");
      turnOnMs += durationOf(batch, "turnAvailabilityOn");

      if (batch.getCandidateCount() == 0) {
        break;
      }

      batchCount++;
      candidateCount += batch.getCandidateCount();
      turnedOnCount += batch.getSpIds().size();
      cursor = batch.getLastSpId();

      if (batch.isExhausted()) {
        break;
      }
      truncated = batchCount >= MAX_BATCHES;
    }

    log.info(
        "event=workflow_completed workflow_id={} window_start={} window_end={} candidate_count={} "
            + "turned_on_count={} batch_count={} truncated={} find_eligible_ms={} turn_on_ms={}",
        workflowId,
        window.getWindowStart(),
        window.getWindowEnd(),
        candidateCount,
        turnedOnCount,
        batchCount,
        truncated,
        findEligibleMs,
        turnOnMs);
  }

  /**
   * Null-safe read of one query's duration. Returns {@code 0} rather than throwing if the map or
   * the key is absent — a missing timing must never be the thing that wedges a run (rule 11).
   */
  private static long durationOf(SpKitCohortBatch batch, String queryName) {
    Map<String, Long> durations = batch.getQueryDurationsMs();
    if (durations == null) {
      return 0;
    }
    Long ms = durations.get(queryName);
    return ms == null ? 0 : ms;
  }
}
