package com.yesmadam.schedulerservice.jobs.raindelay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The slice of WeatherAPI's {@code /v1/current.json} response this job reads.
 *
 * <p>{@code ignoreUnknown} at every level: the real response carries a dozen blocks this job has no
 * use for, and the provider adding a field must not start failing deserialization.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeatherApiResponse {

  private Current current;

  /** {@code Current} and {@code Condition} stay nested — they are parts of one JSON document. */
  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Current {

    /** {@code precip_mm} — the only signal the rain-risk decision uses. */
    @JsonProperty("precip_mm")
    private Double precipMm;

    private Condition condition;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Condition {

    private Integer code;
    private String text;
  }
}
