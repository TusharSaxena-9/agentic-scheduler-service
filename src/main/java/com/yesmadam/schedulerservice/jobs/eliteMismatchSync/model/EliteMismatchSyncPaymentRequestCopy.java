package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Mirrors tracking's {@code PaymentRequestCopy} exactly, including its loosely-typed {@code
 * Object paymentInvoices} field (holds a {@code List<EliteMismatchSyncPaymentInvoice>} at
 * runtime — same shortcut the original takes). Only a subset of fields are set by this job's
 * {@code buildPaymentRequest} — {@code docNum}/{@code address}/{@code checkAccount}/{@code
 * reference1}/{@code reference2}/{@code counterReference}/{@code remarks}/{@code
 * journalRemarks}/{@code cashSumFC}/{@code cashSumSys}/{@code payToCode}/{@code uoi} are left at
 * their Java defaults, matching the original.
 */
@Data
public class EliteMismatchSyncPaymentRequestCopy {

  @JsonProperty("DocNum")
  private int docNum;

  @JsonProperty("DocType")
  private String docType;

  @JsonProperty("DocDate")
  private String docDate;

  @JsonProperty("CardCode")
  private String cardCode;

  @JsonProperty("Address")
  private String address;

  @JsonProperty("CashAccount")
  private String cashAccount;

  @JsonProperty("DocCurrency")
  private String docCurrency;

  @JsonProperty("CashSum")
  private double cashSum;

  @JsonProperty("CheckAccount")
  private String checkAccount;

  @JsonProperty("TransferAccount")
  private String transferAccount;

  @JsonProperty("TransferSum")
  private double transferSum;

  @JsonProperty("TransferDate")
  private String transferDate;

  @JsonProperty("TransferReference")
  private String transferReference;

  @JsonProperty("DocRate")
  private double docRate;

  @JsonProperty("Reference1")
  private String reference1;

  @JsonProperty("Reference2")
  private String reference2;

  @JsonProperty("CounterReference")
  private String counterReference;

  @JsonProperty("Remarks")
  private String remarks;

  @JsonProperty("JournalRemarks")
  private String journalRemarks;

  @JsonProperty("TaxDate")
  private String taxDate;

  @JsonProperty("Series")
  private int series;

  @JsonProperty("BankCode")
  private String bankCode;

  @JsonProperty("BankAccount")
  private String bankAccount;

  @JsonProperty("DiscountPercent")
  private double discountPercent;

  @JsonProperty("ProjectCode")
  private String projectCode;

  @JsonProperty("CashSumFC")
  private double cashSumFC;

  @JsonProperty("CashSumSys")
  private double cashSumSys;

  @JsonProperty("PayToCode")
  private String payToCode;

  @JsonProperty("IsPayToBank")
  private String isPayToBank;

  @JsonProperty("DocTypte")
  private String docTypte;

  @JsonProperty("DueDate")
  private String dueDate;

  @JsonProperty("BPLID")
  private int bplID;

  @JsonProperty("DocObjectCode")
  private String docObjectCode;

  @JsonProperty("PaymentInvoices")
  private Object paymentInvoices;

  @JsonProperty("U_TI")
  private String uti;

  @JsonProperty("U_OI")
  private Long uoi;
}
