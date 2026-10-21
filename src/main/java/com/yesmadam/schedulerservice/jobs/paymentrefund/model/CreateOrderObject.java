package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * The subset of app-server create-order's {@code object} field this job actually needs. The real
 * response carries far more (address, banners, coupons, ...) — only the fields used for the
 * "payment_booking_created" WhatsApp template are modeled here.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateOrderObject {

  /** The short id shown to the customer (e.g. "vQ88BbZ5Pk") — not the internal transaction id. */
  private String doid;

  private String oid;

  @JsonProperty("booking_date")
  private String bookingDate;

  @JsonProperty("booking_time")
  private String bookingTime;
}
