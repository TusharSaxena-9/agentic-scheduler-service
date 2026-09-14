package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.model.BatchResultErrorEntry;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchResult;
import com.amazonaws.services.sqs.model.SendMessageBatchResultEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryCandidateMessage;

@ExtendWith(MockitoExtension.class)
class WalletExpirySqsProducerTest {

  private static final String QUEUE_URL =
      "https://sqs.ap-south-1.amazonaws.com/098102135019/wallet-expiry-notification-uat";

  @Mock AmazonSQSAsync amazonSqs;

  private WalletExpirySqsProducer producer;

  @BeforeEach
  void setUp() {
    producer = new WalletExpirySqsProducer(amazonSqs, new ObjectMapper());
  }

  private static List<WalletExpiryCandidateMessage> messages(int count) {
    return IntStream.rangeClosed(1, count)
        .mapToObj(i -> new WalletExpiryCandidateMessage((long) i, 30, 9000000000L + i, 250, 500, 1760000000000L))
        .collect(Collectors.toList());
  }

  @Test
  void publishBatch_shouldDoNothingForAnEmptyList() {
    producer.publishBatch(QUEUE_URL, List.of(), ProgressReporter.noOp());

    verify(amazonSqs, org.mockito.Mockito.never()).sendMessageBatch(any(SendMessageBatchRequest.class));
  }

  @Test
  void publishBatch_shouldSplitIntoGroupsOfTenAndReportCumulativeProgress() {
    when(amazonSqs.sendMessageBatch(any(SendMessageBatchRequest.class)))
        .thenReturn(new SendMessageBatchResult());

    List<Integer> reported = new ArrayList<>();
    producer.publishBatch(QUEUE_URL, messages(12), reported::add);

    ArgumentCaptor<SendMessageBatchRequest> captor = ArgumentCaptor.forClass(SendMessageBatchRequest.class);
    verify(amazonSqs, org.mockito.Mockito.times(2)).sendMessageBatch(captor.capture());

    List<SendMessageBatchRequest> requests = captor.getAllValues();
    assertThat(requests.get(0).getQueueUrl()).isEqualTo(QUEUE_URL);
    assertThat(requests.get(0).getEntries()).hasSize(10);
    assertThat(requests.get(1).getEntries()).hasSize(2);
    assertThat(requests.get(0).getEntries().get(0).getMessageBody()).contains("\"userId\":1");

    assertThat(reported).containsExactly(10, 12);
  }

  @Test
  void publishBatch_shouldThrowWhenSqsReportsAFailedEntry() {
    SendMessageBatchResult resultWithFailure = new SendMessageBatchResult();
    resultWithFailure.setFailed(List.of(new BatchResultErrorEntry()
        .withId("0").withCode("InternalError").withMessage("boom").withSenderFault(false)));
    when(amazonSqs.sendMessageBatch(any(SendMessageBatchRequest.class))).thenReturn(resultWithFailure);

    assertThatThrownBy(() -> producer.publishBatch(QUEUE_URL, messages(1), ProgressReporter.noOp()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom");
  }

  @Test
  void publishBatch_shouldStillThrowWhenOnlySomeEntriesInTheBatchFail() {
    // entry "0" (user_id 1) succeeds, entry "1" (user_id 2) fails — the successful-entry logging
    // must not interfere with the still-throws-on-partial-failure behavior.
    SendMessageBatchResult partialResult = new SendMessageBatchResult();
    partialResult.setSuccessful(List.of(new SendMessageBatchResultEntry().withId("0")));
    partialResult.setFailed(List.of(new BatchResultErrorEntry()
        .withId("1").withCode("InternalError").withMessage("boom").withSenderFault(false)));
    when(amazonSqs.sendMessageBatch(any(SendMessageBatchRequest.class))).thenReturn(partialResult);

    assertThatThrownBy(() -> producer.publishBatch(QUEUE_URL, messages(2), ProgressReporter.noOp()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom");
  }
}
