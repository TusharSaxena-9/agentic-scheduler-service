package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sap_series} — the AR-invoice and incoming-payment series for this hub's warehouse. */
@Data
@Entity
@Table(name = "tbl_sap_series", catalog = DBConstants.YESMADAM_ADMIN)
public class EliteMismatchSyncSapSeries {

  @Id private Integer id;

  @Column(name = "sapWhsCode")
  private String sapWhsCode;

  @Column(name = "arInvoiceSeries")
  private Integer arInvoiceSeries;

  @Column(name = "incomingPaymentSeries")
  private Integer incomingPaymentSeries;
}
