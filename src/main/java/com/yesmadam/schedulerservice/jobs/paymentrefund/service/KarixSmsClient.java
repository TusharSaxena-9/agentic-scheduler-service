package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Ports {@code Utilities.sendSMSToCustomerWithNewFromKarix} — the refund-failure SMS shortlink
 * sends whenever {@code OrderRefunded.refundedcheck} is true. Same hardcoded URL/key/template/
 * sender as the source (not moved to config here, to stay a faithful port rather than widen scope).
 */
@Slf4j
@Component
public class KarixSmsClient {

  private static final String SMS_URL_TEMPLATE =
      "https://japi.instaalerts.zone/httpapi/QueryStringReceiver?ver=1.0&key=XqfFKQdBLfbEgVsZxWzIPQ==&encrpt=0&templateid=%s&dest=%s&send=YESMDM&text=%s";
  private static final String REFUND_TEMPLATE_ID = "1007196490514347458";
  private static final String REFUND_MESSAGE =
      "Hi! Your payment for the YesMadam booking failed. Any debited amount will get refunded"
          + " within 5-7 days in the original payment mode. Please try booking again.";

  private final RestTemplate restTemplate;

  public KarixSmsClient(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(10))
            .build();
  }

  /** Sends the same refund-failure notice shortlink sends on every successful refund. */
  public void sendRefundNotice(Long mobileNumber) {
    if (mobileNumber == null) {
      log.error("event=refund_sms_skipped reason=no_mobile_number");
      return;
    }
    try {
      String url =
          String.format(
              SMS_URL_TEMPLATE,
              REFUND_TEMPLATE_ID,
              mobileNumber,
              URLEncoder.encode(REFUND_MESSAGE, StandardCharsets.UTF_8));
      // Built via URI.create + the URI-typed overload, not the String overload: RestTemplate's
      // default UriComponentsBuilder re-encodes a String URL, which would double-encode the
      // URLEncoder output above (e.g. "%20" -> "%2520"). A pre-built URI is used as-is.
      restTemplate.getForEntity(URI.create(url), String.class);
      log.info("event=refund_sms_sent mobile={}", mobileNumber);
    } catch (RuntimeException e) {
      log.error("event=refund_sms_failed mobile={} error={}", mobileNumber, e.getMessage());
    }
  }
}
