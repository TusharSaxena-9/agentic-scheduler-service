package com.yesmadam.schedulerservice.common.clevertap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEventPayload;
import com.yesmadam.schedulerservice.common.sqs.SqsPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CleverTapEventProducerTest {

  @Mock SqsPublisher sqsPublisher;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private CleverTapEventProducer producer() {
    CleverTapEventProducer producer = new CleverTapEventProducer(sqsPublisher, objectMapper);
    ReflectionTestUtils.setField(
        producer, "queueUrl", "https://sqs.ap-south-1.amazonaws.com/000/test.fifo");
    return producer;
  }

  @Test
  void publish_shouldSkipWhenEventsAreEmpty() {
    producer().publish(List.of());

    verify(sqsPublisher, never()).sendMessage(anyString(), anyString(), anyString());
  }

  @Test
  void publish_shouldSendOneMessagePerEventWithAPerSpGroupId() {
    CleverTapEventPayload event101 =
        new CleverTapEventPayload(101, "SP_Campaign_Push", Map.of("msgTitle", "hi"));
    CleverTapEventPayload event202 =
        new CleverTapEventPayload(202, "SP_Campaign_Push", Map.of("msgTitle", "hi"));

    producer().publish(List.of(event101, event202));

    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqsPublisher)
        .sendMessage(
            eq("https://sqs.ap-south-1.amazonaws.com/000/test.fifo"),
            bodyCaptor.capture(),
            eq("sp-tomorrow-job-reminder-101"));
    verify(sqsPublisher)
        .sendMessage(
            eq("https://sqs.ap-south-1.amazonaws.com/000/test.fifo"),
            any(),
            eq("sp-tomorrow-job-reminder-202"));

    assertThat(bodyCaptor.getValue()).contains("\"identity\":101").contains("SP_Campaign_Push");
  }
}
