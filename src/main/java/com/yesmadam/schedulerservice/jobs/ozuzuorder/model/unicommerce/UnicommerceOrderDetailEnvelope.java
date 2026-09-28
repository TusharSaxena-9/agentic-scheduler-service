package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** Top-level envelope Unicommerce wraps every {@code saleorder/get} response in. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceOrderDetailEnvelope {

  private boolean successful;
  private UnicommerceOrderDetail saleOrderDTO;
}
