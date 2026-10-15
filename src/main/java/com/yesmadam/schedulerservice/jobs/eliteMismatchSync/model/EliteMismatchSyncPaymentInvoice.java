package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** Mirrors tracking's {@code PaymentInvoice}. {@code appliedSys} is never set by this job — left at its Java default (0.0). */
@Data
public class EliteMismatchSyncPaymentInvoice {

  @JsonProperty("DocEntry")
  private String docEntry;

  @JsonProperty("SumApplied")
  private double sumApplied;

  @JsonProperty("AppliedSys")
  private double appliedSys;

  @JsonProperty("InvoiceType")
  private String invoiceType;
}
