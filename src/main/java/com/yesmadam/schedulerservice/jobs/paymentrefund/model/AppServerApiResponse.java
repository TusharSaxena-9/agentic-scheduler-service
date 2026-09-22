package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** Common {@code {status_code, status, message}} envelope app-server's userapi returns. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppServerApiResponse {

  @JsonProperty("status_code")
  private int statusCode;

  private String status;
  private String message;

  /** Only present on the create-order response; null for the OTP-verify response. */
  private CreateOrderObject object;
}
