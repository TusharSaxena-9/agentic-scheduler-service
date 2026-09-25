package com.yesmadam.schedulerservice.common.clevertap.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.common.clevertap.exception.CleverTapException;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEvent;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapUploadRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CleverTapEventService {
  private static final int MAX_EVENTS_PER_UPLOAD = 1000;

  private final RestTemplate restTemplate;
  private final String accountId;
  private final String passcode;
  private final String userAccountId;
  private final String userPasscode;
  private final String uploadUrl;

  public CleverTapEventService(
      RestTemplate restTemplate,
      @Value("${cleaver.tap.product.id:RK4-W96-WR7Z}") String accountId,
      @Value("${cleaver.tap.passcode:EHA-QUY-YWEL}") String passcode,
      @Value("${cleaver.tap.user.product.id:}") String userAccountId,
      @Value("${cleaver.tap.user.passcode:}") String userPasscode,
      @Value("${cleaver.tap.url:https://in1.api.clevertap.com/1/upload}") String uploadUrl) {
    this.restTemplate = restTemplate;
    this.accountId = requireConfigured("cleaver.tap.product.id", accountId);
    this.passcode = requireConfigured("cleaver.tap.passcode", passcode);
    this.userAccountId = requireConfigured("cleaver.tap.user.product.id", userAccountId);
    this.userPasscode = requireConfigured("cleaver.tap.user.passcode", userPasscode);
    this.uploadUrl = requireConfigured("cleaver.tap.url", uploadUrl);
  }

  /** Uploads to the SP/employee CleverTap project. */
  public int sendEvents(List<CleverTapEvent> events) {
    return upload(events, accountId, passcode, "sp");
  }

  public int sendUserEvents(List<CleverTapEvent> events) {
    return upload(events, userAccountId, userPasscode, "user");
  }

  private int upload(
      List<CleverTapEvent> events, String acct, String pass, String audience) {
    if (events == null || events.isEmpty()) {
      return 0;
    }
    if (events.size() > MAX_EVENTS_PER_UPLOAD) {
      throw new IllegalArgumentException(
          "CleverTap upload batch of "
              + events.size()
              + " exceeds the per-request maximum of "
              + MAX_EVENTS_PER_UPLOAD
              + "; chunk before calling");
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add("X-CleverTap-Account-Id", acct);
    headers.add("X-CleverTap-Passcode", pass);
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<CleverTapUploadRequest> request =
        new HttpEntity<>(new CleverTapUploadRequest(events), headers);

    try {
      ResponseEntity<String> response =
          restTemplate.postForEntity(uploadUrl, request, String.class);

      log.info(
          "event=clevertap_uploaded audience={} count={} status={} body={}",
          audience,
          events.size(),
          response.getStatusCode(),
          response.getBody());
      return events.size();
    } catch (HttpStatusCodeException e) {
      log.error(
          "event=clevertap_upload_failed audience={} count={} status={} body={}",
          audience,
          events.size(),
          e.getRawStatusCode(),
          e.getResponseBodyAsString());
      throw new CleverTapException(
          "CleverTap upload of " + events.size() + " events returned " + e.getRawStatusCode(),
          e.getRawStatusCode());
    } catch (RuntimeException e) {
      log.error(
          "event=clevertap_upload_failed audience={} count={} error={}",
          audience,
          events.size(),
          e.getMessage());
      throw new CleverTapException("CleverTap upload of " + events.size() + " events failed", e);
    }
  }

  /**
   * Rejects blank values and the placeholders a half-configured environment tends to carry, so the
   * failure is a startup error naming the property rather than a 401 at 00:30.
   */
  private static String requireConfigured(String property, String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!StringUtils.hasText(trimmed)
        || "localhost".equalsIgnoreCase(trimmed)
        || "changeme".equalsIgnoreCase(trimmed)) {
      throw new IllegalStateException(
          property
              + " is not configured. CleverTap credentials come from Secrets Manager "
              + "(<env>/scheduler-service); set it there rather than in a committed properties "
              + "file.");
    }
    return trimmed;
  }
}
