package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sap_series}: SAP document numbering series per warehouse. */
@Data
@Entity
@Table(name = "tbl_sap_series", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookSapSeries {

  @Id private Integer id;

  private String sapWhsCode;

  private Integer salesOrderSeries;

  private Integer arInvoiceSeries;

  private Integer incomingPaymentSeries;

  private Integer outgoingPaymentSeries;
}
