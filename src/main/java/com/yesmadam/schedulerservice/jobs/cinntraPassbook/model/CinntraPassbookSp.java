package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sp} — just the fields this job reads. */
@Data
@Entity
@Table(name = "tbl_sp", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookSp {

  @Id private Integer id;

  private String name;

  @Column(name = "hub_id")
  private Integer hubId;
}
