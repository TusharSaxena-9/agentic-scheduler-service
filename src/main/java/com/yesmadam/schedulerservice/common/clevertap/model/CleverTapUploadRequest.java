package com.yesmadam.schedulerservice.common.clevertap.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of a CleverTap {@code /1/upload} call: a single field {@code d} holding the batch.
 *
 * <p>The one-letter name is CleverTap's, not a typo — hence the explicit {@code @JsonProperty}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CleverTapUploadRequest {

  @JsonProperty("d")
  private List<CleverTapEvent> d;
}
