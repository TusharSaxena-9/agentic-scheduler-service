package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.service;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryReminderChunkResult;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryRunWindow;

/**
 * Business logic for the wallet-expiry reminder enqueue job. Imports nothing from {@code
 * io.temporal} so it stays testable with plain JUnit.
 */
public interface WalletExpiryReminderService {

  /**
   * Fetches every candidate for {@code window} in one query and publishes each to {@code
   * queueUrl}. Throws if the publish fails in a way a retry might fix, so the activity lets it
   * fail the whole window and Temporal retries.
   *
   * @param progressReporter heartbeated after each group of published candidates
   */
  WalletExpiryReminderChunkResult processWindow(
      WalletExpiryRunWindow window, String queueUrl, ProgressReporter progressReporter);
}
