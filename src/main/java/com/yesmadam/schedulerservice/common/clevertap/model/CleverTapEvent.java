package com.yesmadam.schedulerservice.common.clevertap.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in a CleverTap {@code /1/upload} batch — the {@code {identity, type, evtName, evtData}}
 * envelope the API expects.
 *
 * <p>Field names are wire format, not Java style, so they are pinned with {@code @JsonProperty}
 * rather than left to Jackson's defaults: {@code evtName} would serialize as {@code evtName}
 * anyway, but relying on that means a future rename or a naming-strategy change on the {@code
 * ObjectMapper} silently produces a payload CleverTap accepts with a 200 and then drops.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CleverTapEvent {

  /** The CleverTap identity the event is attributed to — the SP id, as a string. */
  @JsonProperty("identity")
  private String identity;

  /** Always {@code "event"} for this envelope; {@code "profile"} uploads use a different shape. */
  @JsonProperty("type")
  private String type;

  /** The campaign-facing event name. */
  @JsonProperty("evtName")
  private String evtName;

  /** Arbitrary event properties. Kept a map so callers own the shape their campaign reads. */
  @JsonProperty("evtData")
  private Map<String, Object> evtData;

  /** Builds an event of {@code type=event}, which is the only type this service sends. */
  public static CleverTapEvent of(String identity, String evtName, Map<String, Object> evtData) {
    return new CleverTapEvent(identity, "event", evtName, evtData);
  }
}
