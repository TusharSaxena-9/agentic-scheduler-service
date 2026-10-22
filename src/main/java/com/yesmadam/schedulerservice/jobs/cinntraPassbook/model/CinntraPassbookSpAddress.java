package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_sp_address} — read only for the SAP address-retry PATCH. */
@Data
@Entity
@Table(name = "tbl_sp_address", catalog = DBConstants.YESMADAM_ADMIN)
public class CinntraPassbookSpAddress {

  @Id private Integer id;

  @Column(name = "sp_id")
  private Integer spId;

  private String address1;

  private String city;

  private Integer pincode;
}
