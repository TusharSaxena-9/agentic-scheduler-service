package com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappComponentParameterRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataResponse;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataTemplateRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateLanguageRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends the {@code feedback_addon_1} utility template: four body variables and two quick-reply
 * buttons whose payloads carry the booking id back with the customer's tap.
 */
@Slf4j
@Service
public class BillingCheckWhatsappService {

  /** Payload prefixes the inbound-reply handler must match. Agree these with whoever builds it. */
  public static final String PAYLOAD_YES = "BILLING_CHECK_YES:";

  public static final String PAYLOAD_NO = "BILLING_CHECK_NO:";

  // Same Secrets Manager key as the feedback-reminder sender: both are customer-facing feedback templates.
  @Value("${feedbackreminder.whatsapp.auth-key.token}")
  private String authKeyToken;

  @Value("${com.tata.sms.send.url}")
  private String whatsappUrl;

  private final RestTemplate restTemplate;

  public BillingCheckWhatsappService(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /** @throws BillingCheckSendException on any provider or transport failure. */
  public void send(String recipientMobile, String templateName, List<String> bodyParams, long orderId) {
    List<WhatsappComponentParameterRequest> bodyParameters =
        bodyParams.stream()
            .map(value -> new WhatsappComponentParameterRequest("text", value))
            .collect(Collectors.toList());

    WhatsappTemplateComponentRequest body = new WhatsappTemplateComponentRequest();
    body.setType("body");
    body.setParameters(bodyParameters);

    WhatsappTataTemplateRequest template = new WhatsappTataTemplateRequest();
    template.setName(templateName);
    template.setLanguage(new WhatsappTemplateLanguageRequest("en"));
    template.setComponents(
        List.of(
            body,
            new BillingCheckQuickReplyComponent("0", PAYLOAD_YES + orderId),
            new BillingCheckQuickReplyComponent("1", PAYLOAD_NO + orderId)));

    BillingCheckWhatsappRequest req = new BillingCheckWhatsappRequest();
    req.setTo("+91" + recipientMobile);
    req.setType("template");
    req.setSource("internal");
    req.setSourceType("platform");
    req.setTemplate(template);

    try {
      HttpHeaders headers = new HttpHeaders();
      headers.add("Authorization", authKeyToken);
      headers.add("Content-Type", "application/json");
      restTemplate.postForEntity(
          whatsappUrl, new HttpEntity<>(req, headers), WhatsappTataResponse.class);
      log.info("event=billing_check_whatsapp_sent order_id={} template={}", orderId, templateName);
    } catch (RuntimeException ex) {
      throw new BillingCheckSendException("billing-check send failed: " + ex.getMessage(), ex);
    }
  }
}
