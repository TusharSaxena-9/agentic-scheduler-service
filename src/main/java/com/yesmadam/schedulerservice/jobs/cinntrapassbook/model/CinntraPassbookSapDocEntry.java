package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Mirrors tracking's {@code tbl_sap_doc_entries}. Written on a successful SAP response. */
@Data
@Entity
@Table(name = "tbl_sap_doc_entries", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookSapDocEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "DocEntry")
  private String docEntry;

  private String sapId;

  private Integer orderId;

  private Integer requestType;

  @Column(name = "journal_type_code")
  private Integer journalTypeCode;
}
