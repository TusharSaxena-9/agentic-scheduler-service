package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_user_address} — the customer's default delivery address. */
@Data
@Entity
@Table(name = "tbl_user_address", catalog = DBConstants.YESMADAM_USERS)
public class EliteMismatchSyncAddress {

  @Id private Long id;

  @Column(name = "user_id")
  private Long userId;

  private String address;

  private String address1;

  /** String, not numeric — tracking's own {@code Address#pincode} is a {@code String} too. */
  private String pincode;

  private String city;

  private String state;

  @Column(name = "isDefault")
  private Boolean isDefault;

  @Column(name = "hub_id")
  private Integer hubId;
}
