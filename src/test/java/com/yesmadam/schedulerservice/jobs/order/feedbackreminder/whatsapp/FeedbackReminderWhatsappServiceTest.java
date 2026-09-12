package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataResponse;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;

@ExtendWith(MockitoExtension.class)
class FeedbackReminderWhatsappServiceTest {

  @Mock RestTemplate restTemplate;

  private FeedbackReminderWhatsappService service;

  @BeforeEach
  void setUp() {
    service = new FeedbackReminderWhatsappService(restTemplate);
  }

  private void configure() {
    ReflectionTestUtils.setField(service, "authKeyToken", "test-token");
    ReflectionTestUtils.setField(service, "whatsappUrl", "https://tata.example/send");
    ReflectionTestUtils.setField(
        service, "rateNowBaseUrl", "https://www.yesmadam.com/share?deeplinkCode=27&orderId=");
  }

  @Test
  void sendFeedbackReminderTemplate_shouldThrowWhenBaseUrlNotConfigured() {
    // rateNowBaseUrl left at its default (empty) — authKeyToken/whatsappUrl never matter here.
    assertThatThrownBy(
            () ->
                service.sendFeedbackReminderTemplate(
                    "9876543210", "service_feedback_rating", "Priya", List.of("Facial"), 202L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("feedbackreminder.whatsapp.rate-now.base-url");
    verifyNoInteractions(restTemplate);
  }

  @Test
  void sendFeedbackReminderTemplate_shouldSkipWhenBodyParamsEmpty() {
    configure();

    service.sendFeedbackReminderTemplate(
        "9876543210", "service_feedback_rating", "Priya", List.of(), 202L);

    verifyNoInteractions(restTemplate);
  }

  @Test
  void sendFeedbackReminderTemplate_shouldBuildHeaderBodyAndButtonComponents() {
    configure();
    when(restTemplate.postForEntity(any(String.class), any(), eq(WhatsappTataResponse.class)))
        .thenReturn(ResponseEntity.ok(new WhatsappTataResponse()));

    service.sendFeedbackReminderTemplate(
        "9876543210",
        "service_feedback_rating",
        "Priya",
        List.of("Facial", "202", "06 Sep 2026"),
        202L);

    ArgumentCaptor<HttpEntity<WhatsappTataRequest>> captor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .postForEntity(eq("https://tata.example/send"), captor.capture(), eq(WhatsappTataResponse.class));

    WhatsappTataRequest sent = captor.getValue().getBody();
    assertThat(sent.getTo()).isEqualTo("+919876543210");
    assertThat(sent.getSource()).isEqualTo("internal");
    assertThat(sent).isInstanceOf(FeedbackReminderWhatsappRequest.class);
    assertThat(((FeedbackReminderWhatsappRequest) sent).getSourceType()).isEqualTo("platform");
    assertThat(sent.getTemplate().getName()).isEqualTo("service_feedback_rating");

    List<WhatsappTemplateComponentRequest> components = sent.getTemplate().getComponents();
    assertThat(components).hasSize(3);

    WhatsappTemplateComponentRequest header = components.get(0);
    assertThat(header.getType()).isEqualTo("header");
    assertThat(header.getParameters()).hasSize(1);
    assertThat(header.getParameters().get(0).getText()).isEqualTo("Priya");

    WhatsappTemplateComponentRequest body = components.get(1);
    assertThat(body.getType()).isEqualTo("body");
    assertThat(body.getParameters()).hasSize(3);
    assertThat(body.getParameters().get(1).getText()).isEqualTo("202");

    WhatsappButtonComponentRequest button = (WhatsappButtonComponentRequest) components.get(2);
    assertThat(button.getType()).isEqualTo("button");
    assertThat(button.getSubType()).isEqualTo("URL");
    assertThat(button.getIndex()).isEqualTo("0");
    assertThat(button.getParameters()).hasSize(1);
    assertThat(button.getParameters().get(0).getText())
        .isEqualTo("https://www.yesmadam.com/share?deeplinkCode=27&orderId=202");
  }

  @Test
  void sendFeedbackReminderTemplate_shouldWrapRestTemplateFailure() {
    configure();
    when(restTemplate.postForEntity(any(String.class), any(), eq(WhatsappTataResponse.class)))
        .thenThrow(new RuntimeException("network error"));

    assertThatThrownBy(
            () ->
                service.sendFeedbackReminderTemplate(
                    "9876543210", "service_feedback_rating", "Priya", List.of("Facial"), 202L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to send WhatsApp alert to 9876543210");
  }
}
