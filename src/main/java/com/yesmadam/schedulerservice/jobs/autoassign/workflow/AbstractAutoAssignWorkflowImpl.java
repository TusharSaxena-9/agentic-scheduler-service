package com.yesmadam.schedulerservice.jobs.autoassign.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.google.common.collect.Lists;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoassign.activity.AutoAssignActivities;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignOutcome;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignCandidate;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignRunContext;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

/**
 * The auto-assign orchestration, shared by the same-day and next-day workflows.
 *
 * <p>Replaces two of cron's {@code AutoAssignComponent} methods — {@code
 * findNonAssignJobAndAssignAServiceProviderToJob} (D0) and {@code ...ToJobTomorrow} (D-1). They
 * differ only in the booking date, whether a lead-time floor applies, and one gate term, all
 * carried by {@link AutoAssignScope}. The orchestration itself — chunking, retry boundaries, the
 * distribution hand-off — is identical, so it lives here once.
 *
 * <p><b>Why two workflow types rather than one taking the scope as an argument.</b> A schedule in
 * {@code schedules.yml} derives both its schedule ID and its workflow ID from the job's {@code
 * name} and passes no arguments (rule 7), so one workflow type cannot back two schedules there. Two
 * thin subclasses fit that model and keep the derived IDs distinct, which also matters because the
 * two windows overlap between 16:00 and 21:59 and Temporal refuses a second run under a workflow ID
 * that is already running.
 *
 * <p>Pure orchestration. No clock reads, no injection, no I/O, no AOP on the workflow method (rule
 * 1); logging goes through {@link Workflow#getLogger} so lines are not re-emitted on replay (rule
 * 2). No {@code tbl_cron_time} bookkeeping — Temporal already records start, end, status and
 * retries, and the counts a business consumer would want are returned by the activities and land in
 * workflow history (rule 8).
 */
abstract class AbstractAutoAssignWorkflowImpl {

  private static final Logger log = Workflow.getLogger(AbstractAutoAssignWorkflowImpl.class);

  /**
   * Orders per chunk activity; each chunk is one retryable step.
   *
   * <p><b>Sized so the chunk fits its tier, not for round numbers.</b> A chunk fires its assign
   * calls to app-server sequentially at 5s connect / 10s read, so 100 orders is a ~17-minute worst
   * case — which would put this job past `standard` and onto `heavy`, where it cannot go: `heavy`
   * has six slots for the overnight aggregations, and this job fires 264 times a day. Rule 4's
   * answer applies — the fix is not fewer attempts, it is smaller attempts — so the chunk is sized
   * to keep its worst case inside `standard`: 25 x 10s is ~4 minutes, and the realistic case where
   * each assign returns in well under a second is a few seconds.
   *
   * <p>Smaller chunks also tighten the retry boundary: a failure now costs 25 orders of re-work
   * rather than 100.
   */
  private static final int CHUNK_SIZE = 25;

  /**
   * Orders per distribution dispatch, matching cron's {@code
   * JobDistributionConstants#BATCH_CHUNK_SIZE}. Batching in the workflow rather than the activity
   * makes each batch independently retryable.
   */
  private static final int DISTRIBUTION_BATCH_SIZE = 200;

  /**
   * One set of options for the whole interface — the three methods have similar profiles, so
   * splitting them would cost clarity for nothing (rule 4).
   *
   * <p><b>Placeholder timings.</b> No production numbers exist for this job yet. The bound that
   * matters is the chunk: {@link #CHUNK_SIZE} sequential assign calls at 5s connect / 10s read is a
   * ~4-minute worst case, so 8 minutes is roughly 2x that and leaves room for the context fetch on
   * the same stub. Replace once one real run is observed.
   *
   * <p><b>Heartbeat per order.</b> A chunk can exceed a minute, which is rule 10's threshold, and
   * the failure it guards against is specific: one hung assign call would otherwise hold a
   * `standard` slot and its database connection for the whole {@code startToClose}, and under SKIP
   * overlap every tick firing in the meantime is dropped silently — no failed workflow, no alert.
   * Two minutes of silence is well past the 10s read timeout, so it only trips on a real stall.
   *
   * <p>{@code scheduleToClose} is set as well as {@code startToClose}: without it the last retry
   * can be silently truncated, and an omitted field inherits from nothing. 3 x 8m plus backoff fits
   * inside 30m.
   */
  private final AutoAssignActivities autoAssignActivities =
      Workflow.newActivityStub(
          AutoAssignActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(8))
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  /** Which booking day this workflow targets. */
  protected abstract AutoAssignScope scope();

  /**
   * Runs one auto-assign pass: fetch the run context once, auto-assign chunk by chunk, then hand
   * everything that failed the gate to Auto Job Distribution.
   *
   * <p>Distribution is dispatched after the assign phase, not inside it, so it sees the run's
   * complete candidate set exactly like the cron's single {@code triggerDistributionBatch} call per
   * tick. Each batch reaches the distribution engine in this same service — no HTTP hop — and opens
   * the fire gate once, so a whole run distributes at most as many orders as it dispatched batches,
   * and usually far fewer: the gate stays shut while any distribution is live.
   *
   * <p><b>Resume semantics, and why these are plain {@code for} loops.</b> Every activity call is
   * recorded in workflow history, so on replay a completed chunk returns its recorded result and is
   * not re-executed — chunks 1..n-1 are never redone because chunk n failed. If a chunk exhausts
   * its retries the workflow fails and the remaining batches do not run, which is intended: those
   * orders stay unqueued and the next scheduled tick re-derives them from {@code tbl_order},
   * exactly how the cron recovered. Do not add a catch-and-continue here — it would turn a loud
   * failure into a partial run that looks successful.
   */
  protected void runPass() {
    AutoAssignScope scope = scope();
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={} scope={}", workflowId, scope);

    AutoAssignRunContext runContext = autoAssignActivities.fetchAutoAssignRunContext(scope);

    int totalEvaluated = 0;
    int totalTriggered = 0;
    int totalFailed = 0;
    List<Long> distributionCandidates = new ArrayList<>();

    for (List<AutoAssignCandidate> chunk :
        Lists.partition(runContext.getCandidates(), CHUNK_SIZE)) {
      AutoAssignChunkResult result =
          autoAssignActivities.processAutoAssignChunk(chunk, runContext.getThresholds(), scope);
      totalEvaluated += result.getEvaluated();
      totalTriggered += result.count(AutoAssignOutcome.ASSIGN_TRIGGERED);
      totalFailed += result.count(AutoAssignOutcome.ASSIGN_FAILED);
      distributionCandidates.addAll(result.getDistributionOrderIds());
    }

    int totalSentToDistribution = 0;
    int totalEnqueued = 0;
    int totalFired = 0;
    for (List<Long> batch : Lists.partition(distributionCandidates, DISTRIBUTION_BATCH_SIZE)) {
      DistributionTriggerResult result = autoAssignActivities.dispatchDistributionBatch(batch);
      totalSentToDistribution += result.getReceived();
      totalEnqueued += result.getEnqueued();
      totalFired += result.getFired();
    }

    log.info(
        "event=workflow_completed workflow_id={} scope={} booking_date={} evaluated={}"
            + " assign_triggered={} assign_failed={} sent_to_distribution={} enqueued={} fired={}",
        workflowId,
        scope,
        runContext.getBookingDate(),
        totalEvaluated,
        totalTriggered,
        totalFailed,
        totalSentToDistribution,
        totalEnqueued,
        totalFired);
  }
}
