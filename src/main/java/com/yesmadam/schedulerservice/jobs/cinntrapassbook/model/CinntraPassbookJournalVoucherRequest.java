package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * SAP {@code JournalVouchersService_Add} body. Copy of tracking's {@code
 * req.JournalVoucherRequest}, including its string-typed {@code Debit}/{@code Credit}.
 */
@Data
public class CinntraPassbookJournalVoucherRequest {

  @JsonProperty("JournalVoucher")
  private JournalVoucher journalVoucher;

  /** The {@code JournalVoucher} wrapper SAP expects around the entry. */
  @Data
  public static class JournalVoucher {

    @JsonProperty("JournalEntry")
    private JournalEntry journalEntry;
  }

  /** The voucher's journal entry header. */
  @Data
  public static class JournalEntry {

    @JsonProperty("DueDate")
    private String dueDate;

    @JsonProperty("JournalEntryLines")
    private List<JournalEntryLine> journalEntryLines;

    @JsonProperty("ReferenceDate")
    private String referenceDate;

    @JsonProperty("Memo")
    private String memo;

    @JsonProperty("U_VNO")
    private Long voucherNumber;
  }

  /** One line of the voucher. {@code Debit}/{@code Credit} are strings, as tracking sends them. */
  @Data
  public static class JournalEntryLine {

    @JsonProperty("BPLID")
    private Integer bplId;

    @JsonProperty("AccountCode")
    private String accountCode;

    @JsonProperty("Credit")
    private String credit;

    @JsonProperty("Debit")
    private String debit;

    @JsonProperty("LineMemo")
    private String lineMemo;

    @JsonProperty("ShortName")
    private String shortName;

    @JsonProperty("Reference1")
    private String reference1;

    @JsonProperty("ContraAccount")
    private String contraAccount;
  }
}
