package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.model.BatchResultErrorEntry;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageBatchResult;
import com.amazonaws.services.sqs.model.SendMessageBatchResultEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryCandidateMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Publishes wallet-expiry candidates to an SQS queue in batches of 10 ({@code
 * SendMessageBatch}'s own limit). Not atomic — a batch with any failed entry throws, so the
 * whole page fails and Temporal retries it.
 */
@Slf4j
@Component
public class WalletExpirySqsProducer {

  private static final int SQS_MAX_BATCH_SIZE = 10;

  private final AmazonSQSAsync amazonSqs;
  private final ObjectMapper objectMapper;

  public WalletExpirySqsProducer(AmazonSQSAsync amazonSqs, ObjectMapper objectMapper) {
    this.amazonSqs = amazonSqs;
    this.objectMapper = objectMapper;
  }

  /** Publishes every message to {@code queueUrl} in groups of {@value SQS_MAX_BATCH_SIZE}, heartbeating after each group. */
  public void publishBatch(String queueUrl, List<WalletExpiryCandidateMessage> messages,
      ProgressReporter progressReporter) {
    if (CollectionUtils.isEmpty(messages)) {
      return;
    }
    int sent = 0;
    for (int start = 0; start < messages.size(); start += SQS_MAX_BATCH_SIZE) {
      int end = Math.min(start + SQS_MAX_BATCH_SIZE, messages.size());
      sendOneBatch(queueUrl, messages.subList(start, end));
      sent += (end - start);
      progressReporter.report(sent);
    }
  }

  private void sendOneBatch(String queueUrl, List<WalletExpiryCandidateMessage> batch) {
    List<SendMessageBatchRequestEntry> entries = new ArrayList<>();
    for (int i = 0; i < batch.size(); i++) {
      entries.add(new SendMessageBatchRequestEntry(String.valueOf(i), toJson(batch.get(i))));
    }

    SendMessageBatchResult result;
    try {
      result = amazonSqs.sendMessageBatch(new SendMessageBatchRequest(queueUrl, entries));
    } catch (RuntimeException ex) {
      log.info(
          "event=wallet_expiry_enqueue_send_failed queue={} user_ids={} error={}",
          queueUrl, userIds(batch), ex.getMessage());
      throw ex;
    }

    if (!CollectionUtils.isEmpty(result.getSuccessful())) {
      log.info(
          "event=wallet_expiry_enqueue_send_succeeded queue={} user_ids={}",
          queueUrl, succeededUserIds(batch, result.getSuccessful()));
    }

    if (!CollectionUtils.isEmpty(result.getFailed())) {
      String reasons = result.getFailed().stream()
          .map(BatchResultErrorEntry::getMessage)
          .collect(Collectors.joining("; "));
      log.info(
          "event=wallet_expiry_enqueue_partial_failure queue={} user_ids={} failed={} of {} reasons={}",
          queueUrl, userIds(batch), result.getFailed().size(), batch.size(), reasons);
      throw new IllegalStateException(
          "SQS publish failed for " + result.getFailed().size() + " of " + batch.size()
              + " message(s) on " + queueUrl + ": " + reasons);
    }
  }

  private static List<Long> userIds(List<WalletExpiryCandidateMessage> batch) {
    return batch.stream().map(WalletExpiryCandidateMessage::getUserId).collect(Collectors.toList());
  }

  /** Maps each successful entry back to its {@code user_id} via the index used as its request {@code id}. */
  private static List<Long> succeededUserIds(
      List<WalletExpiryCandidateMessage> batch, List<SendMessageBatchResultEntry> successful) {
    return successful.stream()
        .map(entry -> batch.get(Integer.parseInt(entry.getId())).getUserId())
        .collect(Collectors.toList());
  }

  private String toJson(WalletExpiryCandidateMessage message) {
    try {
      return objectMapper.writeValueAsString(message);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize " + message, e);
    }
  }
}
