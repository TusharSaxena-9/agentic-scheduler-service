package com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A warehouse's SAP numbering series, from {@code tbl_sap_series}.
 *
 * <p>Read-only DTO filled by an HQL constructor expression (CLAUDE.md rule 16), so the field order
 * is the constructor order the query relies on. Never crosses the activity boundary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CinntraPassbookSapSeriesRow {

  private Integer id;

  private String sapWhsCode;

  private Integer salesOrderSeries;

  private Integer arInvoiceSeries;

  private Integer incomingPaymentSeries;

  private Integer outgoingPaymentSeries;
}
