package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.workflow;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.activity.SpKitApprovedNotifyActivities;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * The first half of the kit-approval flow: at 20:00, tell every active SP whose <b>first</b> kit
 * assignment was approved yesterday that their availability is being switched on tonight.
 *
 * <p>The switch itself is {@link SpAvailabilityAutoOnWorkflowImpl} at 23:30, on the same cohort —
 * both jobs resolve the same window (yesterday, whole day, IST) on the same date, so an SP is told
 * at 20:00 and live three and a half hours later. The two are separate schedules rather than one
 * workflow with a timer because a 3.5-hour {@code Workflow.sleep} would hold a run open across the
 * evening, and under SKIP overlap a wedged one would silently suppress the next day's notification
 * as well as its own flip.
 *
 * <p><b>They are coupled only by the window, not by state.</b> Neither reads the other's output. If
 * the 20:00 run fails entirely, the 23:30 run still switches those SPs on — they simply get no
 * heads-up. That is the right failure direction: the SP being live is the outcome that matters, the
 * push is a courtesy.
 *
 * <p>Resolve the window once, then loop: claim a page, push to that page, advance the cursor. The
 * claim is what makes this job converge — unlike the flip, it changes nothing its own eligibility
 * query reads, so without a marker row a retry would re-push everyone. See {@code
 * SpKitApprovedNotifyWriter} for why claiming before sending is the least-bad ordering.
 *
 * <p><b>A failed push does not fail the run.</b> Failures are counted and surface as {@code
 * send_failed_batches} on the completion line; the run itself succeeds. Failing it would, with this
 * job's {@code pause-on-failure: true}, pause the schedule and cost tomorrow's SPs their heads-up
 * over today's CleverTap blip. The cost of that choice is that a permanently broken integration is
 * quiet unless something alerts on that counter — see {@code docs/observability/README.md}.
 *
 * <p>A batch whose push failed is <b>not</b> retried tomorrow: it is already claimed, and
 * tomorrow's window has moved on. Under-notifying once is the deliberate choice over re-notifying,
 * since a CleverTap event cannot be taken back.
 *
 * <p>Pure orchestration otherwise — no Spring injection, no clock reads, no {@code @Loggable} on
 * {@link #run()} (rule 1); {@link Workflow#getLogger} rather than {@code @Slf4j} (rule 2); no
 * {@code tbl_cron_time} (rule 8). The only logic here is counter arithmetic and a null-safe map
 * read, neither of which can throw — which matters while {@code failWorkflowExceptionTypes} is
 * unset service-wide (rule 11), since an exception here would wedge the run rather than fail it.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpKitApprovedNotifyWorkflowImpl implements SpKitApprovedNotifyWorkflow {

  private static final Logger log = Workflow.getLogger(SpKitApprovedNotifyWorkflowImpl.class);

  /**
   * Hard stop on the batch loop. Nothing should approach it — one day of first-time kit approvals
   * against a batch size of 500 — so it guarantees termination rather than setting capacity. A
   * literal because workflow code cannot read configuration (rule 1), and because changing it would
   * change the command sequence of in-flight runs.
   */
  private static final int MAX_BATCHES = 200;

  /**
   * Window resolution and the per-batch claim take the "query or batch write over a bounded set"
   * shape from rule 4's defaults table. <b>Placeholder</b>: unmeasured, and the eligibility query
   * currently aggregates {@code tbl_product_kit_assign_to_sp} in full per page and does an
   * unindexed existence check per candidate (see {@code ProductKitApprovalRepository}) — that is
   * what the 5-minute per-attempt bound is really absorbing. Replace once a real run is observed.
   *
   * <p>The push gets the outbound-send row (1m/5m/3 attempts) via a per-method override. Map keys
   * are activity type names, set explicitly on the interface rather than defaulted from the method
   * name; a key matching no type is silently ignored, so the two move together. Three attempts is
   * right despite CleverTap having no dedup key: the realistic failure is a transport error where
   * nothing was delivered, and a duplicate push is a far cheaper mistake than a silently dropped
   * one.
   */
  private final SpKitApprovedNotifyActivities activities =
      Workflow.newActivityStub(
          SpKitApprovedNotifyActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "SpKitNotifySend",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    SpAvailabilityAutoOnWindow window = activities.resolveRunWindowNotification();

    int cursor = 0;
    int candidateCount = 0;
    int claimedCount = 0;
    int notifiedCount = 0;
    int batchCount = 0;
    int sendFailedBatches = 0;
    boolean truncated = false;
    long findMs = 0;
    long claimMs = 0;

    while (batchCount < MAX_BATCHES) {
      SpKitCohortBatch batch = activities.claimNextBatch(window, cursor);

      findMs += durationOf(batch, "findUnnotifiedEligibleSpIds");
      claimMs += durationOf(batch, "claimForNotification");

      if (batch.getCandidateCount() == 0) {
        break;
      }

      batchCount++;
      candidateCount += batch.getCandidateCount();
      claimedCount += batch.getSpIds().size();
      cursor = batch.getLastSpId();

      // Only after the claim has committed, and only for the SPs this run actually claimed.
      if (!batch.getSpIds().isEmpty()) {
        try {
          notifiedCount += activities.notifyKitApproved(batch.getSpIds());
        } catch (ActivityFailure e) {
          sendFailedBatches++;
          log.warn(
              "event=clevertap_notify_failed workflow_id={} batch={} sp_count={} error={}",
              workflowId,
              batchCount,
              batch.getSpIds().size(),
              e.getMessage());
        }
      }

      if (batch.isExhausted()) {
        break;
      }
      truncated = batchCount >= MAX_BATCHES;
    }

    log.info(
        "event=workflow_completed workflow_id={} window_start={} window_end={} candidate_count={} "
            + "claimed_count={} notified_count={} batch_count={} send_failed_batches={} "
            + "truncated={} find_ms={} claim_ms={}",
        workflowId,
        window.getWindowStart(),
        window.getWindowEnd(),
        candidateCount,
        claimedCount,
        notifiedCount,
        batchCount,
        sendFailedBatches,
        truncated,
        findMs,
        claimMs);
  }

  /**
   * Null-safe read of one query's duration. Returns {@code 0} rather than throwing if the map or
   * key is absent — a missing timing must never be the thing that wedges a run (rule 11).
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
