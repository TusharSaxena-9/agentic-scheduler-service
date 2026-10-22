package com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The {@code tbl_hub} fields the SAP posting reads.
 *
 * <p>Read-only DTO filled by an HQL constructor expression (CLAUDE.md rule 16), so the field order
 * is the constructor order the query relies on. Never crosses the activity boundary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CinntraPassbookHubRow {

  private Integer id;

  private String hub;

  private String state;

  private Boolean isFranchise;

  private String sapWhsCode;

  private Integer sapBplId;

  private Float cgst;

  private Float igst;

  private Float sgst;
}
