package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** The shipping-package fields {@code OzuzuOrderWriter} maps onto {@code OzuzuOrder}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceShippingPackage {

  private String code;
  private String status;
  private String shippingCourier;
  private String trackingNumber;
}
