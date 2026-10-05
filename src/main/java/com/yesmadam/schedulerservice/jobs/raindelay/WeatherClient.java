package com.yesmadam.schedulerservice.jobs.raindelay;

import java.net.URI;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * Current-conditions lookup against WeatherAPI.com ({@code /v1/current.json}), one call per hub.
 *
 * <p>Rain risk is decided on {@code precip_mm} alone, against a threshold from {@code tbl_settings}.
 * {@code condition.code} and {@code condition.text} are recorded and logged for diagnostics but do
 * not affect the verdict.
 *
 * <p><b>Never throws.</b> Every failure returns {@link HubWeather#UNKNOWN}. The caller runs inside
 * an activity capped at 3 retries, and a propagated outage would burn them to then send nothing.
 */
@Slf4j
@Service
public class WeatherClient {

  @Value("${raindelay.weather.base-url}")
  private String baseUrl;

  @Value("${raindelay.weather.api-key:}")
  private String apiKey;

  @Value("${raindelay.enabled:false}")
  private boolean enabled;

  private final RestTemplate restTemplate;

  public WeatherClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * Fails startup on placeholder config rather than letting the first run discover it.
   *
   * <p>Without a key every hub resolves UNKNOWN, nothing is sent, and every run completes green —
   * indistinguishable from clear weather. Skipped unless {@code raindelay.enabled}, so a local run
   * still starts; set that true wherever the schedule is unpaused.
   */
  @PostConstruct
  void validateConfig() {
    if (!enabled) {
      return;
    }
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new IllegalStateException(
          "raindelay.weather.api-key is not configured — set WEATHER_API_KEY (a WeatherAPI.com key,"
              + " not an OpenWeather one)");
    }
    if (baseUrl == null || baseUrl.trim().isEmpty()) {
      throw new IllegalStateException("raindelay.weather.base-url is not configured");
    }
    log.info("event=rain_delay_config_validated base_url={}", baseUrl);
  }

  /**
   * One poll for one hub. {@code RAINY} here means this reading crossed the bar; whether that
   * becomes a notification depends on the previous poll agreeing.
   *
   * <p>Returns the verdict only. {@code condition.code} and {@code condition.text} are read purely
   * to log — they are what explain a verdict that disagrees with the human-readable condition, e.g.
   * {@code code=1195 text="Heavy rain"} alongside {@code precip_mm=0.8}, below the bar.
   *
   * @param lat {@code tbl_hub.lat} as stored — may be null, blank or malformed
   * @param precipThresholdMm rain-risk bar in mm, resolved from {@code tbl_settings} once per run
   *     and passed in rather than read here — this is called once per hub, and reading config
   *     per call would be a settings query per hub
   */
  public HubWeather weatherAt(
      Integer hubId, String lat, String lng, double precipThresholdMm) {
    if (apiKey == null || apiKey.trim().isEmpty()) {
      log.error(
          "event=weather_lookup_skipped reason=api_key_not_configured hub_id={} "
              + "hint=set WEATHER_API_KEY",
          hubId);
      return HubWeather.UNKNOWN;
    }

    double latitude;
    double longitude;
    try {
      latitude = Double.parseDouble(lat.trim());
      longitude = Double.parseDouble(lng.trim());
    } catch (RuntimeException e) {
      // Data problem on one hub row; must not cost the other hubs their lookup.
      log.error(
          "event=weather_lookup_skipped reason=unparseable_coordinates hub_id={} lat={} lng={}",
          hubId,
          lat,
          lng);
      return HubWeather.UNKNOWN;
    }

    // WeatherAPI takes a single "q=lat,lon" plus "key=", not the separate params OpenWeather uses.
    URI uri =
        UriComponentsBuilder.fromHttpUrl(baseUrl)
            .queryParam("key", apiKey)
            .queryParam("q", latitude + "," + longitude)
            .queryParam("aqi", "no")
            .build()
            .toUri();

    try {
      WeatherApiResponse response = restTemplate.getForObject(uri, WeatherApiResponse.class);
      if (response == null || response.getCurrent() == null) {
        log.warn("event=weather_lookup_empty hub_id={}", hubId);
        return HubWeather.UNKNOWN;
      }

      WeatherApiResponse.Current current = response.getCurrent();
      Double precipMm = current.getPrecipMm();
      Integer code = current.getCondition() == null ? null : current.getCondition().getCode();
      String text = current.getCondition() == null ? null : current.getCondition().getText();

      // precip_mm alone. The condition code is recorded for diagnostics but takes no part in the
      // decision — one signal, and no dependence on a transcribed code list staying correct.
      boolean rainRisk = precipMm != null && precipMm >= precipThresholdMm;

      log.info(
          "event=weather_lookup hub_id={} rain_risk={} precip_mm={} threshold_mm={} code={} text={}",
          hubId,
          rainRisk,
          precipMm,
          precipThresholdMm,
          code,
          text);

      return rainRisk ? HubWeather.RAINY : HubWeather.CLEAR;
    } catch (RuntimeException e) {
      // The key is in the URI, so log the type only — never the message or the URI.
      log.error(
          "event=weather_lookup_failed hub_id={} error_type={}",
          hubId,
          e.getClass().getSimpleName());
      return HubWeather.UNKNOWN;
    }
  }
}
