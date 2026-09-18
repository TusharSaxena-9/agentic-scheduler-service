package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import java.security.MessageDigest;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuRefundResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuVerifyResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Ports {@code Utilities.generatePayUHash}, {@code Utilities.verifyPayUPayment} and the PayU branch
 * of {@code Utilities.getOrderRefundedForNoOrder} from {@code shortlink}. Uses {@link RestTemplate}
 * with a form-urlencoded body instead of shortlink's OkHttp + json-simple, since this service
 * already depends on Jackson/Spring Web and neither of those libraries is otherwise needed here.
 */
@Slf4j
@Component
public class PayuVerificationClient {

  @Value("${com.payu.hash.url}")
  private String payuUrl;

  @Value("${com.payu.merchant.id}")
  private String merchantId;

  @Value("${com.payu.salt}")
  private String salt;

  private static final String SEPARATOR = "|";

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public PayuVerificationClient(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(15))
            .build();
  }

  /** SHA-512 hex digest — byte-for-byte the same output as {@code Utilities.generatePayUHash}. */
  public static String generateHash(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-512");
      byte[] bytes = digest.digest(input.getBytes());
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-512 unavailable", e);
    }
  }

  /**
   * @return the transaction_details entry for {@code transactionId}, or {@code null} on failure.
   */
  public PayuVerifyResult verifyPayment(String transactionId) {
    try {
      String hashInput =
          merchantId + SEPARATOR + "verify_payment" + SEPARATOR + transactionId + SEPARATOR + salt;
      String hash = generateHash(hashInput);

      String formBody =
          "key=" + merchantId + "&command=verify_payment&var1=" + transactionId + "&hash=" + hash;
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      String response =
          restTemplate.postForObject(payuUrl, new HttpEntity<>(formBody, headers), String.class);

      JsonNode transactionDetails =
          objectMapper.readTree(response).path("transaction_details").path(transactionId);
      if (transactionDetails.isMissingNode()) {
        log.error("event=payu_verify_no_transaction_details transaction_id={}", transactionId);
        return null;
      }
      return new PayuVerifyResult(
          transactionDetails.path("status").asText(null),
          transactionDetails.path("addedon").asText(null),
          transactionDetails.path("mihpayid").asText(null),
          transactionDetails.path("transaction_amount").asText(null),
          transactionDetails.path("mode").asText(null),
          objectMapper.writeValueAsString(transactionDetails));
    } catch (Exception e) {
      log.error(
          "event=payu_verify_failed transaction_id={} error={}", transactionId, e.getMessage());
      return null;
    }
  }

  /** Ports the {@code paymentGateway == "payu"} branch of {@code getOrderRefundedForNoOrder}. */
  public PayuRefundResult refund(String mihpayid, int amount) {
    long uniqueId = System.currentTimeMillis();
    try {
      String hashInput =
          merchantId
              + SEPARATOR
              + "cancel_refund_transaction"
              + SEPARATOR
              + mihpayid
              + SEPARATOR
              + salt;
      String hash = generateHash(hashInput);

      String formBody =
          "key="
              + merchantId
              + "&command=cancel_refund_transaction&var1="
              + mihpayid
              + "&var2="
              + uniqueId
              + "&var3="
              + amount
              + "&hash="
              + hash;
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      String response =
          restTemplate.postForObject(payuUrl, new HttpEntity<>(formBody, headers), String.class);

      JsonNode json = objectMapper.readTree(response);
      long errorCode = json.path("error_code").asLong(-1);
      long status = json.path("status").asLong(-1);
      String requestId = json.path("request_id").asText("");

      // Same status/error_code → outcome mapping as shortlink's
      // Utilities.getOrderRefundedForNoOrder.
      if (status == 1 && errorCode == 102) {
        return new PayuRefundResult(true, "Refund initiated", requestId, uniqueId);
      } else if (status == 1 && errorCode == 100) {
        return new PayuRefundResult(true, "Refunded", requestId, uniqueId);
      } else if (status == 1 && (errorCode == 101 || errorCode == 225 || errorCode == 226)) {
        return new PayuRefundResult(true, "Pending", requestId, uniqueId);
      } else if (status == 1 && errorCode == 126) {
        return new PayuRefundResult(true, "In progress", requestId, uniqueId);
      } else if (status == 1 && errorCode == 127) {
        return new PayuRefundResult(true, "Requested", requestId, uniqueId);
      }
      return new PayuRefundResult(false, "Failed", requestId, uniqueId);
    } catch (Exception e) {
      log.error("event=payu_refund_failed mihpayid={} error={}", mihpayid, e.getMessage());
      return new PayuRefundResult(false, "Failed", "", uniqueId);
    }
  }
}
