package com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An SP's SAP BusinessPartner code for one type, from {@code tbl_sap_mapping}.
 *
 * <p>Read-only DTO filled by an HQL constructor expression (CLAUDE.md rule 16), so the field order
 * is the constructor order the query relies on. Never crosses the activity boundary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CinntraPassbookSapMappingRow {

  private Integer id;

  private Integer spId;

  private String sapId;

  private Integer typeCode;
}
