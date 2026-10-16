package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

/** What happened to one {@code tbl_checksum_request} row after re-verification. */
public enum PaymentRefundRowOutcome {

  /** Order already existed or a refund was already recorded — the row needed no action. */
  ALREADY_HANDLED,

  /** The gateway confirmed success and {@code /userapi/create-order} created the order. */
  ORDER_CREATED,

  /** The gateway confirmed success but no order resulted, so a refund was issued. */
  REFUND_ISSUED,

  /** The gateway did not confirm success; nothing was created or refunded. */
  NOT_SUCCESSFUL_YET,

  /** {@code payingFor} was not "Service" — this job only handles that case today. */
  UNSUPPORTED_PAYING_FOR,

  /**
   * The row threw before reaching a terminal outcome; safe to retry (idempotency checks guard it).
   */
  FAILED
}
