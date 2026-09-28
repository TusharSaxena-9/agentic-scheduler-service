package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** Top-level envelope Unicommerce wraps every {@code saleOrder/search} response in. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceSearchResponseEnvelope {

  private boolean successful;
  private List<UnicommerceSearchOrderResult> elements;
}
