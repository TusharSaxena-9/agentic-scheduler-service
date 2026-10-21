package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.workflow;

import java.time.Duration;
import java.util.List;

import com.google.common.collect.Lists;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.crontiming.activity.CronTimingActivities;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.activity.LeaveWalletAccrualActivities;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.LeaveWalletAccrualRunContext;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of the monthly SP leave wallet accrual workflow.
 *
 * <p>The workflow drives the chunking: it fetches the run context once (every SP id, the
 * SDL-restricted subset, and settings), then updates tbl_sp_leave_wallet chunk by chunk. Each chunk
 * is its own activity/transaction, so a chunk failure or worker crash resumes from the failed chunk
 * instead of re-running the whole wallet table — and each write only locks a bounded batch of rows
 * instead of the entire table plus tbl_sp/tbl_sp_working_categories/
 * tbl_qr_payment_hub_category_mapping in one long transaction.
 *
 * <p>Pure orchestration only: all side effects (database) go through activities. Do not inject
 * Spring beans or use {@code @Value} here — workflow code must stay deterministic for Temporal
 * replay.
 */
@Slf4j
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class LeaveWalletAccrualWorkflowImpl implements LeaveWalletAccrualWorkflow {

  /** SPs updated per chunk activity; each chunk is one retryable, transactional step. */
  private static final int CHUNK_SIZE = 400;

  /**
   * Activity stub for the accrual run. Timeout covers one chunk (or the context fetch), not the
   * whole wallet table. Attempts are capped so a persistent failure fails the workflow loudly
   * instead of retrying forever and silently blocking the next scheduled run.
   */
  private final LeaveWalletAccrualActivities leaveWalletAccrualActivities =
      Workflow.newActivityStub(
          LeaveWalletAccrualActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  private final CronTimingActivities cronTimingActivities =
      Workflow.newActivityStub(
          CronTimingActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build());

  @Override
  @Loggable(operation = "leave-wallet-accrual-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    Long cronTimingId =
        cronTimingActivities.saveCronStart(
            "LeaveWalletAccrualWorkflow", "run", "Accrue monthly SP leave wallet");

    try {
      log.info("event=workflow_started workflow_id={}", workflowId);

      LeaveWalletAccrualRunContext runContext =
          leaveWalletAccrualActivities.fetchLeaveWalletAccrualRunContext();

      int totalUpdated = 0;
      for (List<Integer> chunk : Lists.partition(runContext.getSpIds(), CHUNK_SIZE)) {
        totalUpdated +=
            leaveWalletAccrualActivities.accrueMonthlyLeaveChunk(
                chunk,
                runContext.getSpaSpIds(),
                runContext.getStandardWoMonthly(),
                runContext.getSpaHydraSdlMonthly());
      }

      log.info(
          "event=workflow_completed workflow_id={} rows_updated={}", workflowId, totalUpdated);

    } finally {
      cronTimingActivities.saveCronEnd(cronTimingId);
    }
  }
}
