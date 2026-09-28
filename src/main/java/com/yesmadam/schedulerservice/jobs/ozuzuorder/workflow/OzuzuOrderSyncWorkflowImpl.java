package com.yesmadam.schedulerservice.jobs.ozuzuorder.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.ozuzuorder.activity.OzuzuOrderSyncActivities;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderCodesBatch;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderSyncResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the Ozuzu order sync workflow: find order codes changed in the trailing
 * window, create the new ones, then refresh the ones that already existed.
 *
 * <p>Pure orchestration — all side effects (HTTP, database) go through activities. No Spring
 * injection or {@code @Value} here; workflow code must stay deterministic for Temporal replay. The
 * window is computed inside the fetch activity rather than here (rule 1), so a replay re-audits
 * the original window instead of one that has slid forward. Logging goes through {@link
 * Workflow#getLogger} rather than a {@code @Slf4j} field, which would re-emit every line on replay
 * (rule 2). No {@code tbl_cron_time} bookkeeping — this is a new job and rule 8 says new jobs must
 * not write it.
 *
 * <p>Create runs to completion before update starts, matching the messagingapis original's
 * sequential {@code processCreateOrders} then {@code processUpdateOrders} — a code the create
 * phase just inserted is visible to the update phase's own existence check (both routed to the
 * writer; see {@code OzuzuOrderWriter}).
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class OzuzuOrderSyncWorkflowImpl implements OzuzuOrderSyncWorkflow {

  private static final Logger log = Workflow.getLogger(OzuzuOrderSyncWorkflowImpl.class);

  /**
   * The search is a fixed handful of Unicommerce calls (one token, two searches) regardless of
   * window contents, so its budget does not need to scale with order volume the way create/update
   * do. {@code CreateOrders} and {@code UpdateOrders} get their own entry because their duration
   * scales with how many order codes the window contains and they heartbeat, unlike the search.
   */
  private final OzuzuOrderSyncActivities activities =
      Workflow.newActivityStub(
          OzuzuOrderSyncActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(10))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "CreateOrders",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(5))
                  .setHeartbeatTimeout(Duration.ofMinutes(1))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(15))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                  .build(),
              "UpdateOrders",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofMinutes(5))
                  .setHeartbeatTimeout(Duration.ofMinutes(1))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(15))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                  .build()));

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    OzuzuOrderCodesBatch batch = activities.fetchPendingOrderCodes();
    List<String> createdSearchCodes =
        batch.getCreatedSearchCodes() == null ? List.of() : batch.getCreatedSearchCodes();
    List<String> updatedSearchCodes =
        batch.getUpdatedSearchCodes() == null ? List.of() : batch.getUpdatedSearchCodes();

    OzuzuOrderSyncResult createResult =
        createdSearchCodes.isEmpty()
            ? new OzuzuOrderSyncResult(0, List.of())
            : activities.createOrders(createdSearchCodes);

    OzuzuOrderSyncResult updateResult =
        updatedSearchCodes.isEmpty()
            ? new OzuzuOrderSyncResult(0, List.of())
            : activities.updateOrders(updatedSearchCodes);

    log.info(
        "event=workflow_completed workflow_id={} window_start={} window_end={} "
            + "created_requested={} created={} create_failed={} "
            + "updated_requested={} updated={} update_failed={}",
        workflowId,
        batch.getWindowStart(),
        batch.getWindowEnd(),
        createdSearchCodes.size(),
        createResult.getSyncedCount(),
        failedCount(createResult),
        updatedSearchCodes.size(),
        updateResult.getSyncedCount(),
        failedCount(updateResult));
  }

  private int failedCount(OzuzuOrderSyncResult result) {
    return result.getFailedOrderCodes() == null ? 0 : result.getFailedOrderCodes().size();
  }
}
