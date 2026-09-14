package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.activity.WalletExpiryReminderActivities;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryReminderChunkResult;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryRunWindow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * Wallet-expiry reminder enqueue workflow: computes today's target window per day-offset, then
 * one retryable activity call per window that fetches every candidate and publishes to SQS. Only
 * enqueues — WhatsApp sending happens downstream wherever the queue is consumed. All four
 * day-offsets share one queue, with the day-offset carried in each message body.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class WalletExpiryReminderWorkflowImpl implements WalletExpiryReminderWorkflow {

  private static final Logger log = Workflow.getLogger(WalletExpiryReminderWorkflowImpl.class);

  /** Timeouts are placeholders. {@code processWalletExpiryWindow} covers a whole day-offset in one call (no paging), so it's sized generously; {@code ComputeRunWindows} is in-memory and trivial. */
  private final WalletExpiryReminderActivities walletExpiryReminderActivities =
      Workflow.newActivityStub(
          WalletExpiryReminderActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(30))
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofHours(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build(),
          Map.of(
              "ComputeRunWindows",
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(30))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(3))
                  .build()));

  @Override
  @Loggable(operation = "wallet-expiry-reminder-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    List<WalletExpiryRunWindow> windows = walletExpiryReminderActivities.computeRunWindows();

    for (WalletExpiryRunWindow window : windows) {
      WalletExpiryReminderChunkResult result =
          walletExpiryReminderActivities.processWalletExpiryWindow(window);

      log.info(
          "event=wallet_expiry_window_completed workflow_id={} day_offset={} wallet_expire_date={} "
              + "published={}",
          workflowId,
          window.getDayOffset(),
          window.getWalletExpireDate(),
          result.getCandidateCount());
    }

    log.info("event=workflow_completed workflow_id={} windows={}", workflowId, windows.size());
  }
}
