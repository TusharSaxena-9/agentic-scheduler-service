package com.yesmadam.schedulerservice.jobs.passbookmismatch.projections;

import java.sql.Timestamp;

/** One flagged {@code tbl_sp_passbook} row as returned by the mismatch query. */
public interface PassbookMismatchProjection {

  Long getPassbookId();

  Integer getSpId();

  Timestamp getPassbookDate();

  String getMismatchReason();

  Integer getOpeningBalance();

  Integer getAmount();

  Integer getClosingBalance();

  String getActionType();

  /** Closing balance of the previous entry — what {@code openingbalance} should have been. */
  Integer getPrevClosing();

  /** {@code opening +/- amount} — what {@code closingbalance} should have been. */
  Integer getExpectedClosing();
}
