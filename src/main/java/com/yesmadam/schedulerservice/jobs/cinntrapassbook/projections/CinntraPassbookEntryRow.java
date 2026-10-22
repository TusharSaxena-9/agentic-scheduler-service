package com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@code tbl_sp_passbook} row, as the SAP posting reads it.
 *
 * <p>Read-only DTO filled by an HQL constructor expression (CLAUDE.md rule 16), so the field order
 * is the constructor order the query relies on. Never crosses the activity boundary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CinntraPassbookEntryRow {

  private Long id;

  private Integer spid;

  private LocalDateTime date;

  private Integer amount;

  private String actiontype;

  private Integer reasonCode;

  private String comment;

  private Long orderId;

  private String paytmtrnxid;

  private Integer isSapProcessed;
}
