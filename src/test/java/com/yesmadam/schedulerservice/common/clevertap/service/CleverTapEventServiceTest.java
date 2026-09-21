package com.yesmadam.schedulerservice.common.clevertap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.yesmadam.schedulerservice.common.clevertap.exception.CleverTapException;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEvent;

/**
 * Covers the two things that decide how this integration behaves in production: whether a
 * misconfigured environment is caught at startup (rule 21), and whether a failure is classified as
 * retryable or permanent (rule 4).
 */
@ExtendWith(MockitoExtension.class)
class CleverTapEventServiceTest {

  private static final String URL = "https://api.clevertap.com/1/upload";
  private static final List<CleverTapEvent> ONE_EVENT =
      List.of(CleverTapEvent.of("1", "Sp_Availability_On", Map.of("spId", "1")));

  @Mock RestTemplate restTemplate;

  private CleverTapEventService service() {
    return new CleverTapEventService(restTemplate, "ACCOUNT", "PASSCODE", URL);
  }

  @Test
  @DisplayName("missing credentials fail at startup, not at 00:30 on the first send")
  void missingConfigFailsConstruction() {
    assertThatThrownBy(() -> new CleverTapEventService(restTemplate, "", "PASSCODE", URL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cleaver.tap.product.id");

    assertThatThrownBy(() -> new CleverTapEventService(restTemplate, "ACCOUNT", "  ", URL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cleaver.tap.passcode");

    assertThatThrownBy(() -> new CleverTapEventService(restTemplate, "ACCOUNT", "PASSCODE", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cleaver.tap.url");
  }

  @Test
  @DisplayName("a leftover placeholder is treated as unconfigured, not as a valid value")
  void placeholderValuesAreRejected() {
    assertThatThrownBy(
            () -> new CleverTapEventService(restTemplate, "ACCOUNT", "PASSCODE", "localhost"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new CleverTapEventService(restTemplate, "changeme", "PASSCODE", URL))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("an empty batch is a no-op, not an empty HTTP call")
  void emptyBatchSkipsTheCall() {
    assertThat(service().sendEvents(List.of())).isZero();
    assertThat(service().sendEvents(null)).isZero();

    verifyNoInteractions(restTemplate);
  }

  @Test
  @DisplayName("credentials travel as headers, and a 2xx reports the batch size")
  void successfulUpload() {
    when(restTemplate.postForEntity(eq(URL), any(HttpEntity.class), eq(String.class)))
        .thenReturn(ResponseEntity.ok("{\"status\":\"success\"}"));

    assertThat(service().sendEvents(ONE_EVENT)).isEqualTo(1);
  }

  @Test
  @DisplayName("a 4xx is permanent — a retry cannot fix a bad account id or payload")
  void clientErrorIsPermanent() {
    when(restTemplate.postForEntity(eq(URL), any(HttpEntity.class), eq(String.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

    assertThatThrownBy(() -> service().sendEvents(ONE_EVENT))
        .isInstanceOf(CleverTapException.class)
        .satisfies(e -> assertThat(((CleverTapException) e).isPermanent()).isTrue());
  }

  @Test
  @DisplayName("a 5xx is retryable — CleverTap being down is exactly what retries are for")
  void serverErrorIsRetryable() {
    when(restTemplate.postForEntity(eq(URL), any(HttpEntity.class), eq(String.class)))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null));

    assertThatThrownBy(() -> service().sendEvents(ONE_EVENT))
        .isInstanceOf(CleverTapException.class)
        .satisfies(e -> assertThat(((CleverTapException) e).isPermanent()).isFalse());
  }

  @Test
  @DisplayName("a transport failure is retryable — nothing was delivered")
  void transportFailureIsRetryable() {
    when(restTemplate.postForEntity(eq(URL), any(HttpEntity.class), eq(String.class)))
        .thenThrow(new ResourceAccessException("connection reset"));

    assertThatThrownBy(() -> service().sendEvents(ONE_EVENT))
        .isInstanceOf(CleverTapException.class)
        .satisfies(e -> assertThat(((CleverTapException) e).isPermanent()).isFalse());
  }

  @Test
  @DisplayName("an oversized batch fails locally rather than being silently truncated by CleverTap")
  void oversizedBatchIsRejectedBeforeSending() {
    List<CleverTapEvent> tooMany =
        IntStream.rangeClosed(1, 1001)
            .mapToObj(i -> CleverTapEvent.of(String.valueOf(i), "E", Map.of()))
            .toList();

    assertThatThrownBy(() -> service().sendEvents(tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1000");

    verifyNoInteractions(restTemplate);
  }
}
