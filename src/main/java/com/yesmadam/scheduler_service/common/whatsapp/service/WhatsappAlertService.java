package com.yesmadam.scheduler_service.common.whatsapp.service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.scheduler_service.common.whatsapp.model.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Service for sending WhatsApp alerts via Tata Communications API. Used by the booking monitor
 * workflow to alert on zero booking counts.
 */
@Slf4j
@Service
public class WhatsappAlertService {

  @Value("${com.tata.sms.auth-key.token}")
  private String authKeyToken;

  @Value("${com.tata.sms.send.url}")
  private String whatsappUrl;

  private final RestTemplate restTemplate;

  public WhatsappAlertService(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * Sends a WhatsApp message to a single recipient using a named template.
   *
   * @param recipientMobile the recipient mobile number (without country code)
   * @param templateName the name of the approved WhatsApp template
   * @param dataList list of parameter values to substitute in the template
   */
  public void sendWhatsappSms(String recipientMobile, String templateName, List<String> dataList) {
    WhatsappTataRequest req = new WhatsappTataRequest();
    req.setTo("+91" + recipientMobile);
    req.setType("template");
    req.setSource("external");

    WhatsappTataTemplateRequest templateRequest = new WhatsappTataTemplateRequest();
    templateRequest.setName(templateName);
    templateRequest.setLanguage(new WhatsappTemplateLanguageRequest("en"));

    List<WhatsappComponentParameterRequest> parameters =
        dataList.stream()
            .map(value -> new WhatsappComponentParameterRequest("text", value))
            .collect(Collectors.toList());

    if (parameters.isEmpty()) {
      log.error(
          "event=whatsapp_skipped reason=empty_parameters mobile={} template={}",
          recipientMobile,
          templateName);
      return;
    }

    WhatsappTemplateComponentRequest componentRequest = new WhatsappTemplateComponentRequest();
    componentRequest.setType("body");
    componentRequest.setParameters(parameters);
    templateRequest.setComponents(Collections.singletonList(componentRequest));

    req.setTemplate(templateRequest);

    sendRequest(req, templateName, recipientMobile);
  }

  /**
   * Executes the HTTP call to Tata Communications WhatsApp API.
   *
   * @param req the fully constructed request object
   * @param templateName template name for logging
   * @param mobile recipient mobile for logging
   */
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
          "event=whatsapp_failed mobile={} template={} error={}",
          mobile,
          templateName,
          ex.getMessage());
      throw new RuntimeException("Failed to send WhatsApp alert to " + mobile, ex);
    }
  }
}
