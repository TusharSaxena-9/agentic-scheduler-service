package com.yesmadam.schedulerservice.jobs.raindelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;


/**
 * The rain-risk bar and every failure path — all of them decide whether a customer gets messaged,
 * and anything thrown here would burn the activity's three retries.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeatherClientTest {

  @Mock RestTemplate restTemplate;
  @InjectMocks WeatherClient client;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(
        client, "baseUrl", "https://api.weatherapi.com/v1/current.json");
    ReflectionTestUtils.setField(client, "apiKey", "test-key");
    ReflectionTestUtils.setField(client, "enabled", true);
  }

  @ParameterizedTest(name = "precip {0}mm, code {1} -> rainy={2}")
  @CsvSource({
    // The whole rule: precip_mm against the threshold, inclusive at the boundary.
    "2.5,  1000, true",
    "9.0,  1000, true",
    "2.49, 1000, false",
    "0.0,  1000, false",
    // The condition code takes no part in the decision. A heavy-rain code with no accumulated
    // precipitation does NOT fire; a clear-sky code with 9mm does.
    "0.0, 1195, false",
    "0.0, 1276, false",
    "9.0, 1000, true",
    // Sample from the ticket: patchy light rain with thunder, 1.42mm — below the bar, no alert.
    "1.42, 1273, false"
  })
  void appliesRainRiskBar(double precipMm, int code, boolean expectedRainy) {
    when(restTemplate.getForObject(any(URI.class), eq(WeatherApiResponse.class)))
        .thenReturn(response(precipMm, code, "test"));

    assertThat(client.weatherAt(4, "20.267", "85.783", 2.5))
        .isEqualTo(expectedRainy ? HubWeather.RAINY : HubWeather.CLEAR);
  }

  @ParameterizedTest(name = "precip 3.0mm against threshold {0} -> rainy={1}")
  @CsvSource({"2.5, true", "3.0, true", "3.01, false", "10.0, false", "1.0, true"})
  @DisplayName("the threshold comes from the caller, not a hardcoded 2.5")
  void honoursCallerThreshold(double threshold, boolean expectedRainy) {
    when(restTemplate.getForObject(any(URI.class), eq(WeatherApiResponse.class)))
        .thenReturn(response(3.0, 1000, "Sunny"));

    assertThat(client.weatherAt(4, "20.267", "85.783", threshold))
        .isEqualTo(expectedRainy ? HubWeather.RAINY : HubWeather.CLEAR);
  }


  @Test
  @DisplayName("a null precip_mm is CLEAR, not an exception — even on a heavy-rain code")
  void nullPrecipIsSafe() {
    when(restTemplate.getForObject(any(URI.class), eq(WeatherApiResponse.class)))
        .thenReturn(response(null, 1195, "Heavy rain"));

    assertThat(client.weatherAt(4, "20.267", "85.783", 2.5))
        .isEqualTo(HubWeather.CLEAR);
  }

  @Test
  @DisplayName("coordinates go as a single q=lat,lon with key, WeatherAPI style")
  void buildsRequestUri() {
    when(restTemplate.getForObject(any(URI.class), eq(WeatherApiResponse.class)))
        .thenReturn(response(0.0, 1000, "Sunny"));

    client.weatherAt(4, " 20.267 ", "85.783", 2.5);

    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    org.mockito.Mockito.verify(restTemplate)
        .getForObject(uri.capture(), eq(WeatherApiResponse.class));
    // Trimmed before parsing: tbl_hub.lat/lng are VARCHAR and carry padding.
    assertThat(uri.getValue().toString())
        .contains("key=test-key")
        .contains("20.267")
        .contains("85.783");
  }

  @Test
  @DisplayName("no API key: UNKNOWN, and no HTTP call attempted")
  void missingApiKeyIsUnknown() {
    ReflectionTestUtils.setField(client, "apiKey", "");

    assertThat(client.weatherAt(4, "20.267", "85.783", 2.5))
        .isEqualTo(HubWeather.UNKNOWN);
    verifyNoInteractions(restTemplate);
  }

  @ParameterizedTest(name = "coordinates ({0}, {1}) -> UNKNOWN")
  @CsvSource(
      value = {"NULL|85.783", "20.267|NULL", "|85.783", "not-a-number|85.783", "20.267|N/A"},
      delimiter = '|',
      nullValues = "NULL")
  @DisplayName("unparseable hub coordinates are UNKNOWN, not an exception")
  void unparseableCoordinatesAreUnknown(String lat, String lng) {
    // A data problem on one hub row must not cost the other hubs their lookup.
    assertThat(client.weatherAt(4, lat, lng, 2.5)).isEqualTo(HubWeather.UNKNOWN);
    verifyNoInteractions(restTemplate);
  }

  @Test
  @DisplayName("a provider error is UNKNOWN, never a thrown exception")
  void providerErrorIsUnknown() {
    when(restTemplate.getForObject(any(URI.class), eq(WeatherApiResponse.class)))
        .thenThrow(new ResourceAccessException("connect timed out"));

    assertThat(client.weatherAt(4, "20.267", "85.783", 2.5))
        .isEqualTo(HubWeather.UNKNOWN);
  }

  @Test
  @DisplayName("an empty or bodyless response is UNKNOWN, not CLEAR")
  void emptyResponseIsUnknown() {
    when(restTemplate.getForObject(any(URI.class), eq(WeatherApiResponse.class)))
        .thenReturn(null);
    assertThat(client.weatherAt(4, "20.267", "85.783", 2.5))
        .isEqualTo(HubWeather.UNKNOWN);

    when(restTemplate.getForObject(any(URI.class), eq(WeatherApiResponse.class)))
        .thenReturn(new WeatherApiResponse());
    assertThat(client.weatherAt(4, "20.267", "85.783", 2.5))
        .isEqualTo(HubWeather.UNKNOWN);
  }

  @Test
  @DisplayName("enabled with no key fails startup rather than sending nothing silently")
  void guardFailsStartupWhenEnabledWithoutKey() {
    ReflectionTestUtils.setField(client, "apiKey", "");

    assertThatThrownBy(() -> client.validateConfig())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WEATHER_API_KEY");
  }

  @Test
  @DisplayName("disabled skips the guard so a local run still starts")
  void guardSkippedWhenDisabled() {
    ReflectionTestUtils.setField(client, "enabled", false);
    ReflectionTestUtils.setField(client, "apiKey", "");

    client.validateConfig();
  }

  private static WeatherApiResponse response(Double precipMm, Integer code, String text) {
    WeatherApiResponse.Condition condition = new WeatherApiResponse.Condition();
    condition.setCode(code);
    condition.setText(text);

    WeatherApiResponse.Current current = new WeatherApiResponse.Current();
    current.setPrecipMm(precipMm);
    current.setCondition(condition);

    WeatherApiResponse response = new WeatherApiResponse();
    response.setCurrent(current);
    return response;
  }
}
