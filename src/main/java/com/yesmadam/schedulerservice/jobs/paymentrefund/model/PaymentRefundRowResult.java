package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bounded result of processing one checksum row — never the row itself (CLAUDE.md rule 9). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRefundRowResult {

  private Long checksumId;
  private PaymentRefundRowOutcome outcome;
  private String detail;
}
