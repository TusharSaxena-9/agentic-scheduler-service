package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_hub} — just the fields this job reads. */
@Data
@Entity
@Table(name = "tbl_hub", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookHub {

  @Id private Integer id;

  private String hub;

  private String state;

  @Column(name = "is_franchise")
  private Boolean isFranchise;

  @Column(name = "sapWhsCode")
  private String sapWhsCode;

  @Column(name = "sapBplId")
  private Integer sapBplId;

  private Float cgst;

  private Float igst;

  private Float sgst;
}
