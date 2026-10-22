package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * One line of {@link CinntraPassbookJournalEntry}. Copy of tracking's {@code req.JournalEntryLine}.
 */
@Data
public class CinntraPassbookJournalEntryLine {

  @JsonProperty("Reference2")
  private String reference2;

  @JsonProperty("DueDate")
  private String dueDate;

  @JsonProperty("AccountCode")
  private String accountCode;

  @JsonProperty("Debit")
  private double debit;

  @JsonProperty("Credit")
  private double credit;

  @JsonProperty("ContraAccount")
  private String contraAccount;

  @JsonProperty("LineMemo")
  private String lineMemo;

  @JsonProperty("Reference1")
  private String reference1;

  @JsonProperty("BPLID")
  private int bplid;

  @JsonProperty("ShortName")
  private String shortName;
}
