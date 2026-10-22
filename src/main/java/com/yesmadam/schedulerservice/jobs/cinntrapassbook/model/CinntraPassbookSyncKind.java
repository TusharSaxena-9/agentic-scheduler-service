package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookEntryRow;

/**
 * The five postings tracking's {@code CinntraPassbook} cron makes, one per selection query.
 *
 * <p>The four daily kinds post one SAP document per key and record it in {@code
 * tbl_sap_doc_entries} under {@link #getDocRequestType()} — the same mapping tracking's {@code
 * PassbookSapSyncService.DOC_ENTRY_TYPES} uses. That record is what lets a retried chunk tell which
 * entries an earlier attempt already posted. {@link #JOURNAL} has no such key (one passbook row can
 * produce several documents, and many rows share order id 0), so it relies on {@code
 * is_sap_processed} instead.
 */
public enum CinntraPassbookSyncKind {

  /**
   * Every 30 minutes: journal entries, journal vouchers and vendor payments for all reason codes.
   */
  JOURNAL(null, null),

  /** 01:00: yesterday's commission (reason 11) as an AR invoice, or a draft for Notion SPs. */
  COMMISSION(11, 57),

  /** 01:00: yesterday's SP convenience fee (reason 102) as an AR invoice, or a draft. */
  CONVENIENCE(102, 102),

  /** 04:00: yesterday's SP wallet recharges (reason 26) as incoming payments. */
  WALLET_RECHARGE(26, 59),

  /** 04:00: yesterday's online booking payments (reason 27) as incoming payments. */
  BOOKING_ONLINE(27, 55);

  private final Integer reasonCode;
  private final Integer docRequestType;

  CinntraPassbookSyncKind(Integer reasonCode, Integer docRequestType) {
    this.reasonCode = reasonCode;
    this.docRequestType = docRequestType;
  }

  /**
   * The {@code tbl_sp_passbook.reasonCode} this kind selects; {@code null} for {@link #JOURNAL}.
   */
  public Integer getReasonCode() {
    return reasonCode;
  }

  /**
   * The {@code tbl_sap_doc_entries.requestType} its document is recorded under; {@code null} for
   * {@link #JOURNAL}.
   */
  public Integer getDocRequestType() {
    return docRequestType;
  }

  /**
   * The {@code tbl_sap_doc_entries.orderId} a posted entry is recorded under: the passbook id for a
   * wallet recharge (it has no order — tracking passes {@code sp.getId()} for exactly this reason),
   * the order id for the rest. {@code null} when the entry has no key, and then no dedupe applies.
   */
  public Long docEntryKey(CinntraPassbookEntryRow entry) {
    return this == WALLET_RECHARGE ? entry.getId() : entry.getOrderId();
  }
}
