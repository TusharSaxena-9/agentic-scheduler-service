package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * Minimal projection of {@code tbl_sp_passbook} — the fields the SAP posting reads. Never written
 * through this entity: {@code is_sap_processed} is set with a single-column update, see {@code
 * CinntraPassbookEntryRepository#updateSapProcessed}.
 */
@Data
@Entity
@Table(name = "tbl_sp_passbook", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookEntry {

  @Id private Long id;

  private Integer spid;

  private LocalDateTime date;

  private Integer amount;

  /** DEBIT or CREDIT. */
  private String actiontype;

  private Integer reasonCode;

  private String comment;

  @Column(name = "order_id")
  private Long orderId;

  private String paytmtrnxid;

  @Column(name = "is_sap_processed")
  private Integer isSapProcessed;
}
