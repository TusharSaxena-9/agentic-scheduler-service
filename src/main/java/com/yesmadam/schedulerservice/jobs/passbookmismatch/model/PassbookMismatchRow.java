package com.yesmadam.schedulerservice.jobs.passbookmismatch.model;

import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One flagged passbook entry, as a plain serializable value.
 *
 * <p>Separate from {@code PassbookMismatchProjection} because this crosses the Temporal activity
 * boundary and gets stored in workflow history — it has to be JSON-serializable, which a Hibernate
 * projection proxy is not.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PassbookMismatchRow {

  private Long passbookId;
  private Integer spId;
  private Timestamp passbookDate;
  private String mismatchReason;
  private Integer openingBalance;
  private Integer amount;
  private Integer closingBalance;
  private String actionType;

  /** What {@code openingBalance} should have been — the previous entry's closing balance. */
  private Integer prevClosing;

  /** What {@code closingBalance} should have been — {@code opening +/- amount}. */
  private Integer expectedClosing;
}
