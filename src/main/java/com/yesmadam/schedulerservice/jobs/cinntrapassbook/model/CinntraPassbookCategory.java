package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_category}: the SAP costing code (CostingCode3) per category. */
@Data
@Entity
@Table(name = "tbl_category", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookCategory {

  @Id private Integer id;

  @Column(name = "sapOcrdCode")
  private String sapOcrdCode;
}
