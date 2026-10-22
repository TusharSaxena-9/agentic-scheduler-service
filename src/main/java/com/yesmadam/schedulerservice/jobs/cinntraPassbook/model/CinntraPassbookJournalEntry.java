package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/** SAP {@code JournalEntries} body. Field-for-field copy of tracking's {@code req.JournalEntry}. */
@Data
public class CinntraPassbookJournalEntry {

  @JsonProperty("Memo")
  private String memo;

  @JsonProperty("Reference2")
  private String reference2;

  @JsonProperty("TransactionCode")
  private String transactionCode;

  @JsonProperty("ProjectCode")
  private String projectCode;

  @JsonProperty("StornoDate")
  private String stornoDate;

  @JsonProperty("VatDate")
  private String vatDate;

  @JsonProperty("DueDate")
  private String dueDate;

  @JsonProperty("LocationCode")
  private String locationCode;

  @JsonProperty("OriginalJournal")
  private String originalJournal;

  @JsonProperty("JournalEntryLines")
  private List<CinntraPassbookJournalEntryLine> journalEntryLines;

  @JsonProperty("Series")
  private int series;

  @JsonProperty("TaxDate")
  private String taxDate;

  @JsonProperty("ReferenceDate")
  private String referenceDate;

  @JsonProperty("U_OI")
  private Long uoi;

  @JsonProperty("Original")
  private int original;

  @JsonProperty("U_VNO")
  private Long voucherNumber;
}
