package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.pg.merchant.PaytmChecksum;
import com.yesmadam.persistence.PaytmStatusResponse;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaytmRefundResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Ports {@code Utilities.validatePaytmTransaction} and the Paytm branch of {@code
 * Utilities.getOrderRefundedForNoOrder} from {@code shortlink}. Uses the same {@code
 * com.paytm.pg:paytm-checksum} library shortlink uses, so the checksum bytes are produced
 * identically.
 */
@Slf4j
@Component
public class PaytmVerificationClient {

  @Value("${com.paytm.MID}")
  private String merchantId;

  @Value("${com.paytm.MERCAHNTKEY}")
  private String merchantKey;

  @Value("${com.paytm.VALIDATE_URL}")
  private String validateUrl;

  @Value("${com.paytm.auto.refundUrl}")
  private String refundUrl;

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public PaytmVerificationClient(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(15))
            .build();
  }

  /**
   * @return the parsed transaction status, or {@code null} if the call failed.
   */
  public PaytmStatusResponse validateTransaction(String paymentSourceId) {
    try {
      TreeMap<String, String> params = new TreeMap<>();
      params.put("MID", merchantId);
      params.put("ORDERID", paymentSourceId);
      String checksum = PaytmChecksum.generateSignature(params, merchantKey);
      params.put("CHECKSUMHASH", checksum);

      String jsonBody = objectMapper.writeValueAsString(params);
      String encodedBody = java.net.URLEncoder.encode(jsonBody, "UTF-8");

      HttpHeaders headers = new HttpHeaders();
      // Matches the header shortlink actually sends today ("contentType", not the standard
      // "Content-Type") — preserved for wire compatibility rather than "corrected".
      headers.set("contentType", "application/json");
      String requestBody = "JsonData=" + encodedBody;

      String response =
          restTemplate.postForObject(
              validateUrl, new HttpEntity<>(requestBody, headers), String.class);
      return objectMapper.readValue(response, PaytmStatusResponse.class);
    } catch (Exception e) {
      log.error(
          "event=paytm_validate_failed payment_source_id={} error={}",
          paymentSourceId,
          e.getMessage());
      return null;
    }
  }

  /** Ports the {@code paymentGateway == "paytm"} branch of {@code getOrderRefundedForNoOrder}. */
  public PaytmRefundResult refund(String orderId, String txnId, String refundAmount) {
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("mid", merchantId);
      body.put("txnType", "REFUND");
      body.put("orderId", orderId);
      body.put("txnId", txnId);
      body.put("refId", "Refundid_" + System.currentTimeMillis());
      body.put("refundAmount", refundAmount);

      String bodyJson = objectMapper.writeValueAsString(body);
      // Sign the exact bytes that get sent as "body" — do not re-serialize afterward.
      String checksum = PaytmChecksum.generateSignature(bodyJson, merchantKey);
      String payload = "{\"body\":" + bodyJson + ",\"head\":{\"signature\":\"" + checksum + "\"}}";

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
      String response =
          restTemplate.postForObject(refundUrl, new HttpEntity<>(payload, headers), String.class);

      JsonNode json = objectMapper.readTree(response);
      String resultStatus = json.path("body").path("resultInfo").path("resultStatus").asText();
      String refundedAmount = json.path("body").path("refundAmount").asText(refundAmount);
      boolean refunded = !"TXN_FAILURE".equalsIgnoreCase(resultStatus);
      return new PaytmRefundResult(refunded, resultStatus, refundedAmount);
    } catch (Exception e) {
      log.error("event=paytm_refund_failed order_id={} error={}", orderId, e.getMessage());
      return new PaytmRefundResult(false, "Failed", refundAmount);
    }
  }
}
