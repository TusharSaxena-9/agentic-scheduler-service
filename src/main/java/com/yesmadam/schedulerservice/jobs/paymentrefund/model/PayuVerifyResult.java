package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Parsed {@code transaction_details} entry from PayU's {@code verify_payment} response. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayuVerifyResult {

  private String status;
  private String addedOn;
  private String mihpayid;
  private String transactionAmount;
  private String mode;

  /**
   * The raw {@code transaction_details} JSON for this transaction, verbatim — stored as-is in
   * {@code PayuTransactionsEntity.txnData}, matching shortlink's {@code
   * transaction_details.toJSONString()}. Carries every field PayU returned, not just the five
   * parsed above.
   */
  private String rawJson;
}
