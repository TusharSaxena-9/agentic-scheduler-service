package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of a Paytm {@code REFUND} call. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaytmRefundResult {

  private boolean refunded;
  private String resultStatus;
  private String refundAmount;
}
