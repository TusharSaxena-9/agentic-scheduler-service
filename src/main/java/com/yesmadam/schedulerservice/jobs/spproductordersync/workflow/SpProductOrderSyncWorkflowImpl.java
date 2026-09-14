package com.yesmadam.schedulerservice.jobs.spproductordersync.workflow;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.spproductordersync.activity.SpProductOrderSyncActivities;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the SP delivered-unavailable-product sync workflow — migrated from
 * messagingapis' {@code SpProductOrderSyncCron#syncDeliveredUnavailableProducts}, a Spring
 * {@code @Scheduled} cron that fired every 2 hours on the hour.
 *
 * <p>Two set-based steps, run in this exact order to match the original:
 *
 * <ol>
 *   <li>Increment SP inventory counters for products that were delivered while still marked
 *       unavailable ({@code is_available=0, order_status=ORDER_PLACED}).
 *   <li>Flip those same rows to {@code is_available=1, order_status=ORDER_DELIVERED}.
 * </ol>
 *
 * <p>Step 2 flips the exact rows step 1's query depends on, so reversing the order would make step
 * 1 count nothing on the next run. A step-1 failure is non-fatal — logged and swallowed so step 2
 * still runs — mirroring the original cron's inner try/catch around the inventory update only. A
 * step-2 failure is left to propagate: it exhausts its retries, the SDK wraps it in {@code
 * ActivityFailure} (a {@code TemporalFailure}), and the workflow fails normally (rule 11).
 *
 * <p>No cron-timing bookkeeping (rule 8) — Temporal's own execution history already records start,
 * end, duration and failure detail, and both steps return their row counts, which land in workflow
 * history and this log line for anyone checking a specific run.
 *
 * <p>Pure orchestration only: all side effects (database) go through activities. Do not inject
 * Spring beans or use {@code @Value} here — workflow code must stay deterministic for Temporal
 * replay (rule 1). Logging goes through {@link Workflow#getLogger} rather than {@code @Slf4j}
 * (rule 2).
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class SpProductOrderSyncWorkflowImpl implements SpProductOrderSyncWorkflow {

  private static final Logger log = Workflow.getLogger(SpProductOrderSyncWorkflowImpl.class);

  /**
   * Activity stub for both sync steps.
   *
   * <p>Default options assume the "query or batch write over a bounded set" shape from CLAUDE.md
   * rule 4's defaults table — unmeasured placeholder, revisit once a real run has been observed.
   * The sync step inherits the default in full: its {@code WHERE is_available = 0} is self-
   * consuming (a retry after a successful commit finds nothing left to flip), so three attempts are
   * safe.
   *
   * <p>The increment step overrides {@code maximumAttempts} down to 1. Unlike the sync step, it is
   * <b>not</b> naturally idempotent: {@code SET products_purchased = ... + agg.total_qty} adds to
   * the running counters rather than converging to a fixed value, and nothing it touches marks the
   * source rows as consumed — a retry after the write commits but before Temporal records the
   * activity's completion (a lost connection on the way back, a worker killed mid-deploy) would
   * re-match the same rows and double-count the inventory (CLAUDE.md rule 14). The original
   * Spring {@code @Scheduled} cron had no retry layer above it at all, so this failure mode could
   * not occur there; capping attempts at 1 here preserves that same safety rather than introducing
   * a new way to double-count. A failure is deferred to the next scheduled tick two hours later,
   * which finds the same unresolved rows and is exactly the original's own recovery path.
   */
  private final SpProductOrderSyncActivities activities =
      Workflow.newActivityStub(
          SpProductOrderSyncActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "IncrementInventoryProductsPurchasedForDelivered",
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofMinutes(10))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    int inventoryRowsUpdated = incrementInventoryQuietly();
    int syncedRowsUpdated = activities.syncDeliveredSpUnavailableProducts();

    log.info(
        "event=workflow_completed workflow_id={} inventory_rows_updated={} synced_rows_updated={}",
        workflowId,
        inventoryRowsUpdated,
        syncedRowsUpdated);
  }

  /**
   * Mirrors the original cron's inner try/catch around the inventory update only: a failure here is
   * logged and swallowed so the sync step still runs, instead of failing the whole workflow.
   *
   * @return rows updated, or {@code -1} if the single attempt failed
   */
  private int incrementInventoryQuietly() {
    try {
      return activities.incrementInventoryProductsPurchasedForDelivered();
    } catch (RuntimeException e) {
      log.error("event=increment_inventory_failed error={}", e.getMessage());
      return -1;
    }
  }
}
