package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** The billing-address fields {@code OzuzuOrderWriter} maps onto a new {@code OzuzuOrder}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceAddress {

  private String name;
  private String addressLine1;
  private String city;
  private String state;
  private String country;
  private String pincode;
}
