package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** Unicommerce's OAuth password-grant token response. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceTokenResponse {

  @JsonProperty("access_token")
  private String accessToken;
}
