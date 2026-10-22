package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sap_mapping}: an SP's SAP BusinessPartner code per type. */
@Data
@Entity
@Table(name = "tbl_sap_mapping", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookSapMapping {

  @Id private Integer id;

  private Integer spId;

  private String sapId;

  private Integer typeCode;
}
