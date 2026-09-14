package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.activity;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryReminderChunkResult;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryRunWindow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for the wallet-expiry reminder enqueue job: one context-fetch
 * activity (today's date + configured day-offsets, turned into windows), then one call per
 * window that fetches and publishes every candidate in it.
 */
@ActivityInterface
public interface WalletExpiryReminderActivities {

  /** Builds today's target window for each configured day-offset (30/7/4/1 by default). */
  @ActivityMethod
  List<WalletExpiryRunWindow> computeRunWindows();

  /** Fetches every candidate for {@code window} and publishes each to SQS. */
  @ActivityMethod
  WalletExpiryReminderChunkResult processWalletExpiryWindow(WalletExpiryRunWindow window);
}
