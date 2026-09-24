package com.yesmadam.schedulerservice.jobs.ozuzuorder.service;

import java.time.Duration;
import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceOrderDetail;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceOrderDetailEnvelope;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceOrderDetailRequest;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceSearchOrderRequest;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceSearchOrderResult;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceSearchResponseEnvelope;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceTokenResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbound HTTP for the Ozuzu Unicommerce tenant — token, order search and order detail.
 *
 * <p><b>Timeouts are set explicitly</b> (5s connect / 15s read), matching {@code
 * AutoAssignApiClient}. The messagingapis original built a plain {@code new RestTemplate()} per
 * call with no timeout at all — an unbounded socket wait inside a Temporal activity would consume
 * the activity's whole {@code startToCloseTimeout} budget for one stuck call.
 *
 * <p><b>One token per activity invocation</b>, not one per HTTP call. The original fetched a fresh
 * token before every search and every per-order detail call — for a window with N new/updated
 * orders that is {@code N + 2} authorization round trips. Callers here fetch a token once via
 * {@link #fetchAccessToken()} and pass it into every {@link #searchOrders} / {@link
 * #fetchOrderDetail} call for that activity execution.
 */
@Slf4j
@Service
public class OzuzuUnicommerceClient {

  @Value("${ozuzuorder.unicommerce.base-url}")
  private String baseUrl;

  @Value("${ozuzuorder.unicommerce.token-url}")
  private String tokenPath;

  @Value("${ozuzuorder.unicommerce.search-orders-url}")
  private String searchOrdersPath;

  @Value("${ozuzuorder.unicommerce.fetch-order-url}")
  private String fetchOrderPath;

  @Value("${ozuzuorder.unicommerce.client-id}")
  private String clientId;

  @Value("${ozuzuorder.unicommerce.grant-type}")
  private String grantType;

  @Value("${ozuzuorder.unicommerce.username}")
  private String username;

  @Value("${ozuzuorder.unicommerce.password}")
  private String password;

  private final RestTemplate restTemplate;

  public OzuzuUnicommerceClient(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(15))
            .build();
  }

  /**
   * Fails startup rather than discovering a placeholder credential the first time the job tries to
   * sync an order (rule 21) — this integration is exercised at most once every ten minutes, so a
   * broken value could otherwise sit unnoticed for a long time.
   */
  @PostConstruct
  void validateConfig() {
    requireConfigured("ozuzuorder.unicommerce.base-url", baseUrl);
    requireConfigured("ozuzuorder.unicommerce.client-id", clientId);
    requireConfigured("ozuzuorder.unicommerce.username", username);
    requireConfigured("ozuzuorder.unicommerce.password", password);
  }

  private void requireConfigured(String property, String value) {
    if (value == null || value.isBlank() || "localhost".equalsIgnoreCase(value.trim())) {
      throw new IllegalStateException("event=ozuzu_unicommerce_config_missing property=" + property);
    }
  }

  /**
   * Requests a fresh OAuth password-grant token. Never throws for a well-formed but unsuccessful
   * response; only a transport failure or a malformed body propagates, both retryable by the
   * activity that calls this.
   */
  public String fetchAccessToken() {
    String url = baseUrl + tokenPath;

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", grantType);
    body.add("client_id", clientId);
    body.add("username", username);
    body.add("password", password);

    ResponseEntity<UnicommerceTokenResponse> response =
        restTemplate.exchange(
            url, HttpMethod.POST, new HttpEntity<>(body, headers), UnicommerceTokenResponse.class);

    UnicommerceTokenResponse token = response.getBody();
    if (token == null || token.getAccessToken() == null || token.getAccessToken().isBlank()) {
      throw new IllegalStateException("event=ozuzu_unicommerce_token_missing status=" + response.getStatusCodeValue());
    }
    return token.getAccessToken();
  }

  /**
   * Order codes Unicommerce reports as matching the given search window and date-type filter. An
   * unsuccessful envelope (Unicommerce's own {@code successful: false}) returns an empty list
   * rather than throwing — the original treated it the same way.
   */
  public List<UnicommerceSearchOrderResult> searchOrders(
      String accessToken, UnicommerceSearchOrderRequest request) {
    String url = baseUrl + searchOrdersPath;

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(accessToken);
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<UnicommerceSearchResponseEnvelope> response =
        restTemplate.exchange(
            url,
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            UnicommerceSearchResponseEnvelope.class);

    UnicommerceSearchResponseEnvelope envelope = response.getBody();
    if (envelope == null || !envelope.isSuccessful() || envelope.getElements() == null) {
      return List.of();
    }
    return envelope.getElements();
  }

  /**
   * Full detail for one order code, including payment detail. Returns {@code null} when
   * Unicommerce reports the request unsuccessful — the caller treats that order as failed for this
   * run and moves on, exactly as the per-order {@code try/catch} in the original did.
   */
  public UnicommerceOrderDetail fetchOrderDetail(String accessToken, String orderCode) {
    String url = baseUrl + fetchOrderPath;

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(accessToken);
    headers.setContentType(MediaType.APPLICATION_JSON);

    UnicommerceOrderDetailRequest request = new UnicommerceOrderDetailRequest(orderCode, true);

    ResponseEntity<UnicommerceOrderDetailEnvelope> response =
        restTemplate.exchange(
            url,
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            UnicommerceOrderDetailEnvelope.class);

    UnicommerceOrderDetailEnvelope envelope = response.getBody();
    if (envelope == null || !envelope.isSuccessful()) {
      log.warn("event=ozuzu_order_detail_unsuccessful order_code={}", orderCode);
      return null;
    }
    return envelope.getSaleOrderDTO();
  }
}
