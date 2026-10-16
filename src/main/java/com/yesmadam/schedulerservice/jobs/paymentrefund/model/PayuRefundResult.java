package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of a PayU {@code cancel_refund_transaction} call. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayuRefundResult {

  private boolean refunded;
  private String resultStatus;
  private String requestId;

  /**
   * The {@code uniqueId} (PayU's {@code var2}) used for this specific refund call — the same value
   * must be recorded on the audit row, or the row can no longer be correlated back to the actual
   * PayU request.
   */
  private long uniqueId;
}
