package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sp_working_categories}: which categories an SP works in. */
@Data
@Entity
@Table(name = "tbl_sp_working_categories", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookWorkingCategory {

  @Id private Integer id;

  @Column(name = "sp_id")
  private Integer spId;

  @Column(name = "cat_id")
  private Integer catId;
}
