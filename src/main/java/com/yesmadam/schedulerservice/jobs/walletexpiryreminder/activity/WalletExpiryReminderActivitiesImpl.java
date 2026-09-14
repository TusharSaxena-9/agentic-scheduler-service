package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.activity;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryReminderChunkResult;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryRunWindow;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.service.WalletExpiryReminderService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of wallet-expiry reminder activities: {@link #computeRunWindows()} builds
 * today's windows per day-offset; {@link #processWalletExpiryWindow} fetches candidates and
 * publishes to SQS. All four day-offsets share one queue ({@code
 * com.yesmadam.wallet-expiry-reminder.queue-url}), with the day-offset carried in the message
 * body. {@link #resolveQueueUrl} fails loudly if unset.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class WalletExpiryReminderActivitiesImpl implements WalletExpiryReminderActivities {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final WalletExpiryReminderService walletExpiryReminderService;

  @Value("#{'${com.yesmadam.wallet-expiry-reminder.day-offsets}'.split(',')}")
  private List<Integer> dayOffsets;

  @Value("${com.yesmadam.wallet-expiry-reminder.queue-url:}")
  private String queueUrl;

  public WalletExpiryReminderActivitiesImpl(WalletExpiryReminderService walletExpiryReminderService) {
    this.walletExpiryReminderService = walletExpiryReminderService;
  }

  @Override
  @Loggable(operation = "compute-wallet-expiry-run-windows")
  @HandleExceptions
  public List<WalletExpiryRunWindow> computeRunWindows() {
    LocalDate today = LocalDate.now(IST);
    return dayOffsets.stream().map(offset -> buildWindow(today, offset)).collect(Collectors.toList());
  }

  private static WalletExpiryRunWindow buildWindow(LocalDate today, int dayOffset) {
    LocalDate walletExpireDate = today.plusDays(dayOffset);
    Timestamp dayStart = Timestamp.valueOf(walletExpireDate.atStartOfDay());
    Timestamp dayEnd = Timestamp.valueOf(walletExpireDate.plusDays(1).atStartOfDay());
    return new WalletExpiryRunWindow(dayOffset, dayStart, dayEnd, walletExpireDate);
  }

  @Override
  @Loggable(operation = "process-wallet-expiry-window")
  @HandleExceptions
  public WalletExpiryReminderChunkResult processWalletExpiryWindow(WalletExpiryRunWindow window) {
    return walletExpiryReminderService.processWindow(window, resolveQueueUrl(), heartbeat());
  }

  private String resolveQueueUrl() {
    if (!StringUtils.hasText(queueUrl)) {
      throw new IllegalStateException(
          "No SQS queue URL configured for wallet-expiry reminders "
              + "(com.yesmadam.wallet-expiry-reminder.queue-url)");
    }
    return queueUrl;
  }

  private static ProgressReporter heartbeat() {
    return completed -> Activity.getExecutionContext().heartbeat(completed);
  }
}
