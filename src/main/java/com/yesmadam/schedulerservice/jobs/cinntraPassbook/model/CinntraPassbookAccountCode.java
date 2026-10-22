package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_payment_action_sap}: SAP GL account per passbook reason code. */
@Data
@Entity
@Table(name = "tbl_payment_action_sap", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookAccountCode {

  @Id private Integer id;

  @Column(name = "account_code")
  private String accountCode;

  @Column(name = "reason_code")
  private Integer reasonCode;
}
