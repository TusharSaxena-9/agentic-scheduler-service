package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for Unicommerce's {@code saleOrder/search}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnicommerceSearchOrderRequest {

  private String fromDate;
  private String toDate;
  private String dateType;
  private String channel;
  private List<String> facilityCodes;
}
