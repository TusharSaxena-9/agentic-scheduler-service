package com.yesmadam.schedulerservice.common.clevertap;

import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEventPayload;
import com.yesmadam.schedulerservice.common.sqs.SqsPublisher;

import lombok.extern.slf4j.Slf4j;


/**
 * Publishes CleverTap events to SQS instead of calling CleverTap directly. cx-consumer owns the
 * consumer side and makes the actual CleverTap upload call — {@link CleverTapEventPayload} is the
 * message contract shared between the two.
 */
@Slf4j
@Service
public class CleverTapEventProducer {

  @Value("${sqs.fifo-queues.sp-tomorrow-job-reminder-clevertap}")
  private String queueUrl;

  private final SqsPublisher sqsPublisher;
  private final ObjectMapper objectMapper;

  public CleverTapEventProducer(SqsPublisher sqsPublisher, ObjectMapper objectMapper) {
    this.sqsPublisher = sqsPublisher;
    this.objectMapper = objectMapper;
  }


  @PostConstruct
  void validateConfig() {
    if (!StringUtils.hasText(queueUrl)) {
      throw new IllegalStateException(
          "CleverTap SQS queue is not configured: "
              + "sqs.fifo-queues.sp-tomorrow-job-reminder-clevertap must be set");
    }
  }

  public void publish(List<CleverTapEventPayload> events) {
    if (events.isEmpty()) {
      return;
    }

    for (CleverTapEventPayload event : events) {
      String messageBody;
      try {
        messageBody = objectMapper.writeValueAsString(event);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(
            "Failed to serialize CleverTap event for identity=" + event.getIdentity(), e);
      }
      sqsPublisher.sendMessage(
          queueUrl, messageBody, "sp-tomorrow-job-reminder-" + event.getIdentity());
    }

    log.info("event=clevertap_sqs_published queue={} count={}", queueUrl, events.size());
  }
}
