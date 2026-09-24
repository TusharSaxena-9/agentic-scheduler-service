package com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for Unicommerce's {@code saleorder/get}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnicommerceOrderDetailRequest {

  private String code;
  private Boolean paymentDetailRequired;
}
