package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.jobs.paymentrefund.model.AppServerApiResponse;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.OtpVerificationRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Calls app-server's userapi to mint a bearer token via a synthetic OTP verification, then create
 * the order — the same two calls {@code PaymentRefundComponent.verifyOTP} and {@code
 * OrderCreate.sendPostRequestWithBearer} made in {@code shortlink}.
 */
@Slf4j
@Component
public class AppServerOrderClient {

  // A dedicated key, not the shared "com.api.yesmadam.base.url" — that one is already used by
  // AutoAssignApiClient with a different value shape (no trailing "/userapi"). This mirrors
  // shortlink's own base.url value verbatim so the concatenated URLs below match production
  // byte-for-byte, whatever their path shape actually is.
  @Value("${com.api.yesmadam.payment-refund.base-url}")
  private String baseUrl;

  @Value("${com.api.verify.otp}")
  private String verifyOtpPath;

  @Value("${com.api.yesmadam.create-order:/userapi/create-order}")
  private String createOrderPath;

  private final RestTemplate restTemplate;

  public AppServerOrderClient(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            // create-order is a heavy synchronous endpoint (OTP verify, slot/address validation,
            // coupon logic, order persistence) — seen in UAT genuinely taking longer than 15s
            // under load. A short timeout here doesn't fail safely: it just makes this job treat
            // an in-flight create-order as "definitely not created" while app-server may still be
            // about to create it (see processPayuRow's create-order-inconclusive handling).
            .setReadTimeout(Duration.ofSeconds(45))
            .build();
  }

  /**
   * @return a {@code "Bearer <token>"} header value, or {@code null} if verification failed.
   */
  public String verifyOtpAndGetBearerToken(OtpVerificationRequest request) {
    try {
      AppServerApiResponse response =
          restTemplate.postForObject(baseUrl + verifyOtpPath, request, AppServerApiResponse.class);
      if (response == null || response.getMessage() == null) {
        log.error("event=otp_verification_empty_response mobile={}", request.getMobile());
        return null;
      }
      return "Bearer " + response.getMessage();
    } catch (RuntimeException e) {
      log.error(
          "event=otp_verification_failed mobile={} error={}", request.getMobile(), e.getMessage());
      return null;
    }
  }

  /**
   * @return the parsed response (check {@code getStatus()} for {@code "success"}), or {@code
   *     null} if the call itself failed. Callers need {@code getObject()} for the fields
   *     (doid/booking_date/booking_time) shown in the "payment_booking_created" WhatsApp template
   *     — a bare success/fail boolean isn't enough anymore.
   */
  public AppServerApiResponse createOrder(Map<String, Object> orderRequest, String bearerToken) {
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
      AppServerApiResponse response =
          restTemplate.postForObject(
              baseUrl + createOrderPath,
              new HttpEntity<>(orderRequest, headers),
              AppServerApiResponse.class);
      boolean success = response != null && "success".equalsIgnoreCase(response.getStatus());
      log.info(
          "event=create_order_called payment_source_id={} success={}",
          orderRequest.get("payment_source_id"),
          success);
      return response;
    } catch (RuntimeException e) {
      log.error(
          "event=create_order_failed payment_source_id={} error={}",
          orderRequest.get("payment_source_id"),
          e.getMessage());
      return null;
    }
  }
}
