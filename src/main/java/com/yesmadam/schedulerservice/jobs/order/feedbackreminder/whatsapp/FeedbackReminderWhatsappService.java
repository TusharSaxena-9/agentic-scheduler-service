package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.whatsapp;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappComponentParameterRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataResponse;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataTemplateRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateLanguageRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FeedbackReminderWhatsappService {


  @Value("${feedbackreminder.whatsapp.auth-key.token}")
  private String authKeyToken;

  @Value("${com.tata.sms.send.url}")
  private String whatsappUrl;


  @Value("${feedbackreminder.whatsapp.rate-now.base-url:}")
  private String rateNowBaseUrl;

  private final RestTemplate restTemplate;

  public FeedbackReminderWhatsappService(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  public void sendFeedbackReminderTemplate(
      String recipientMobile,
      String templateName,
      String customerName,
      List<String> bodyParams,
      Long orderId) {
    if (!StringUtils.hasText(rateNowBaseUrl)) {
      throw new IllegalStateException(
          "feedbackreminder.whatsapp.rate-now.base-url is not set for this environment");
    }

    List<WhatsappComponentParameterRequest> bodyParameters =
        bodyParams.stream()
            .map(value -> new WhatsappComponentParameterRequest("text", value))
            .collect(Collectors.toList());

    if (bodyParameters.isEmpty()) {
      log.error(
          "event=whatsapp_skipped reason=empty_parameters mobile={} template={}",
          recipientMobile,
          templateName);
      return;
    }

    FeedbackReminderWhatsappRequest req = new FeedbackReminderWhatsappRequest();
    req.setTo("+91" + recipientMobile);
    req.setType("template");
    req.setSource("internal");
    req.setSourceType("platform");

    WhatsappTataTemplateRequest templateRequest = new WhatsappTataTemplateRequest();
    templateRequest.setName(templateName);
    templateRequest.setLanguage(new WhatsappTemplateLanguageRequest("en"));

    WhatsappTemplateComponentRequest headerComponent = new WhatsappTemplateComponentRequest();
    headerComponent.setType("header");
    headerComponent.setParameters(
        List.of(new WhatsappComponentParameterRequest("text", customerName)));

    WhatsappTemplateComponentRequest bodyComponent = new WhatsappTemplateComponentRequest();
    bodyComponent.setType("body");
    bodyComponent.setParameters(bodyParameters);

    WhatsappButtonComponentRequest buttonComponent = new WhatsappButtonComponentRequest();
    buttonComponent.setType("button");
    buttonComponent.setSubType("URL");
    buttonComponent.setIndex("0");
    buttonComponent.setParameters(
        List.of(new WhatsappComponentParameterRequest("text", rateNowBaseUrl + orderId)));

    templateRequest.setComponents(List.of(headerComponent, bodyComponent, buttonComponent));
    req.setTemplate(templateRequest);

    sendRequest(req, templateName, recipientMobile);
  }

  private void sendRequest(WhatsappTataRequest req, String templateName, String mobile) {
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.add("Authorization", authKeyToken);
      headers.add("Content-Type", "application/json");

      HttpEntity<WhatsappTataRequest> request = new HttpEntity<>(req, headers);
      ResponseEntity<WhatsappTataResponse> response =
          restTemplate.postForEntity(whatsappUrl, request, WhatsappTataResponse.class);

      log.info(
          "event=whatsapp_sent mobile={} template={} status={}",
          mobile,
          templateName,
          response.getStatusCode());
    } catch (Exception ex) {
      log.error(
          "event=whatsapp_failed mobile={} template={} error={}", mobile, templateName, ex.getMessage());
      throw new RuntimeException("Failed to send WhatsApp alert to " + mobile, ex);
    }
  }
}
