package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappComponentParameterRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataResponse;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTataTemplateRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateComponentRequest;
import com.yesmadam.schedulerservice.common.whatsapp.model.WhatsappTemplateLanguageRequest;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundWhatsappRequest;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.WhatsappButtonComponentRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends the three payment-refund-cron WhatsApp templates through the same Tata Omnichannel
 * account {@code WhatsappAlertService} and {@code FeedbackReminderWhatsappService} already use
 * ({@code com.tata.sms.auth-key.token} / {@code com.tata.sms.send.url}) — new capability, not
 * present in shortlink's original cron, added alongside the existing Karix SMS refund notice
 * rather than replacing it.
 *
 * <p>Not built on {@code WhatsappAlertService}: that service only ever sends a single "body"
 * component. These templates need a "header" component (customer name), and {@code
 * payment_booking_created} a "button" component too, so this is modeled directly on {@code
 * FeedbackReminderWhatsappService} — the one existing caller that already sends that exact
 * [header, body, button] shape (confirmed by that service's own test).
 *
 * <p>Deliberately swallows failures instead of rethrowing (unlike both of those services): a
 * failed notification must never fail the refund/order-creation row it is reporting on.
 */
@Slf4j
@Component
public class PaymentRefundWhatsappClient {

  @Value("${com.tata.sms.auth-key.token-refund}")
  private String authKeyToken;

  @Value("${com.tata.sms.send.url}")
  private String whatsappUrl;

  private final RestTemplate restTemplate;

  public PaymentRefundWhatsappClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * Case 2: a booking was created via the cron.
   *
   * <p>{@code orderId}/{@code scheduledDate}/{@code buttonSuffix} are best-effort — the
   * create-order response and request payload are never parsed for these in the source cron, so
   * there is no confirmed field to read them from yet. Callers should treat these as placeholders
   * pending a real source.
   */
  public void sendBookingCreated(
      String mobile,
      String customerName,
      String orderId,
      String scheduledDate,
      String buttonSuffix) {
      WhatsappButtonComponentRequest button = new WhatsappButtonComponentRequest();
      button.setType("button");
      button.setSubType("URL");
      button.setIndex("0");
      button.setParameters(
        List.of(new WhatsappComponentParameterRequest("text", nullToEmpty(buttonSuffix))));

    send(
        mobile,
        "payment_booking_created",
        customerName,
        List.of(nullToEmpty(orderId), nullToEmpty(scheduledDate)),
        button);
  }

  /** Case 1: a refund was initiated via the cron, for either gateway. */
  public void sendRefundInitiated(String mobile, String customerName, String refundAmount) {
    send(
        mobile,
        "payment_refund_initiated_1",
        customerName,
        List.of(nullToEmpty(refundAmount)),
        null);
  }

  /** Case 3: the cron ran but neither created a booking nor confirmed a refund. */
  public void sendRefundPending(String mobile, String customerName) {
    send(mobile, "payment_refund_pending", customerName, List.of(), null);
  }

  private void send(
      String mobile,
      String templateName,
      String customerName,
      List<String> bodyParams,
      WhatsappButtonComponentRequest button) {
    if (mobile == null) {
      log.error("event=payment_refund_whatsapp_skipped reason=no_mobile template={}", templateName);
      return;
    }
    try {
      PaymentRefundWhatsappRequest req = new PaymentRefundWhatsappRequest();
      req.setTo("+91" + mobile);
      req.setType("template");
      req.setSource("internal");
      req.setSourceType("platform");

      WhatsappTataTemplateRequest templateRequest = new WhatsappTataTemplateRequest();
      templateRequest.setName(templateName);
      templateRequest.setLanguage(new WhatsappTemplateLanguageRequest("en"));

      List<WhatsappTemplateComponentRequest> components = new ArrayList<>();

      WhatsappTemplateComponentRequest header = new WhatsappTemplateComponentRequest();
      header.setType("header");
      header.setParameters(
          List.of(new WhatsappComponentParameterRequest("text", nullToEmpty(customerName))));
      components.add(header);

      if (!bodyParams.isEmpty()) {
        WhatsappTemplateComponentRequest body = new WhatsappTemplateComponentRequest();
        body.setType("body");
        body.setParameters(
            bodyParams.stream()
                .map(value -> new WhatsappComponentParameterRequest("text", value))
                .toList());
        components.add(body);
      }

      if (button != null) {
        components.add(button);
      }

      templateRequest.setComponents(components);
      req.setTemplate(templateRequest);

      HttpHeaders headers = new HttpHeaders();
      headers.add("Authorization", authKeyToken);
      headers.add("Content-Type", "application/json");
      restTemplate.postForEntity(
          whatsappUrl, new HttpEntity<WhatsappTataRequest>(req, headers), WhatsappTataResponse.class);
      log.info("event=payment_refund_whatsapp_sent mobile={} template={}", mobile, templateName);
    } catch (Exception e) {
      log.error(
          "event=payment_refund_whatsapp_failed mobile={} template={} error={}",
          mobile,
          templateName,
          e.getMessage());
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
