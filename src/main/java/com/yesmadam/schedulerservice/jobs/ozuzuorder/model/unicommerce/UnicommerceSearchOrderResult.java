package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * One element of Unicommerce's {@code saleOrder/search} response. Only {@code code} is used —
 * every other field the search response carries is fetched fresh from {@code saleOrder/get} per
 * order, so it is not declared here.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnicommerceSearchOrderResult {

  private String code;
}
