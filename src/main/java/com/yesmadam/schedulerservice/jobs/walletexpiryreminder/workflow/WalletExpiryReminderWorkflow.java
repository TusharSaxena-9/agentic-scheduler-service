package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the wallet-expiry WhatsApp reminder cron. Sends a reminder to every user
 * whose {@code tbl_user.wallet_min} balance expires in 30, 7, 4 or 1 day(s), via Tata Tele's
 * WhatsApp Cloud API.
 *
 * <p>No-arg by design so it fits the shared schedule registrar, which starts every job's workflow
 * with no arguments.
 */
@WorkflowInterface
public interface WalletExpiryReminderWorkflow {

  /** Executes one wallet-expiry reminder run across all configured day-offsets. */
  @WorkflowMethod
  void run();
}
