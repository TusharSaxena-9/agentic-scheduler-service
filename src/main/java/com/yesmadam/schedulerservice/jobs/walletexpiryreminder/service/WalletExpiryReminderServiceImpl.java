package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryCandidateMessage;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryReminderChunkResult;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryRunWindow;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.projections.WalletExpiryCandidateProjection;
import com.yesmadam.schedulerservice.utils.QueryTimer;

@Service
public class WalletExpiryReminderServiceImpl implements WalletExpiryReminderService {

  private final WalletExpiryReminderReader reader;
  private final WalletExpirySqsProducer sqsProducer;

  public WalletExpiryReminderServiceImpl(
      WalletExpiryReminderReader reader, WalletExpirySqsProducer sqsProducer) {
    this.reader = reader;
    this.sqsProducer = sqsProducer;
  }

  @Override
  public WalletExpiryReminderChunkResult processWindow(
      WalletExpiryRunWindow window, String queueUrl, ProgressReporter progressReporter) {
    QueryTimer timer = new QueryTimer();

    List<WalletExpiryCandidateProjection> candidates =
        timer.time("fetchCandidates", () -> reader.fetchCandidates(window.getDayStart(), window.getDayEnd()));

    if (!candidates.isEmpty()) {
      List<WalletExpiryCandidateMessage> messages = candidates.stream()
          .map(c -> new WalletExpiryCandidateMessage(
              c.getUserId(),
              window.getDayOffset(),
              c.getMobile(),
              c.getWalletMin(),
              c.getAmountLimit(),
              c.getExpireAt() != null ? c.getExpireAt().getTime() : null))
          .collect(Collectors.toList());
      sqsProducer.publishBatch(queueUrl, messages, progressReporter);
    }

    WalletExpiryReminderChunkResult result = new WalletExpiryReminderChunkResult(candidates.size());
    result.setQueryDurationsMs(timer.getDurationsMs());
    result.setQueryRowCounts(timer.getRowCounts());
    return result;
  }
}
