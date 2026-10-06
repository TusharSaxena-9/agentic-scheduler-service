package com.yesmadam.schedulerservice.common.sqs;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.model.SendMessageRequest;


/**
 * Thin wrapper over {@link AmazonSQSAsync} for publishing to a FIFO queue. Failures are not
 * swallowed here — an {@code AmazonSQSException} is unchecked and propagates to the caller so a
 * Temporal activity retries it rather than silently losing the message (rule 4).
 */
@Component
public class SqsPublisher {

  private final AmazonSQSAsync amazonSqs;

  public SqsPublisher(AmazonSQSAsync amazonSqs) {
    this.amazonSqs = amazonSqs;
  }

  public void sendMessage(String queueUrl, String messageBody, String messageGroupId) {
    SendMessageRequest request =
        new SendMessageRequest()
            .withQueueUrl(queueUrl)
            .withMessageBody(messageBody)
            .withMessageGroupId(messageGroupId)
            .withMessageDeduplicationId(UUID.randomUUID().toString());

    amazonSqs.sendMessage(request);
  }
}
