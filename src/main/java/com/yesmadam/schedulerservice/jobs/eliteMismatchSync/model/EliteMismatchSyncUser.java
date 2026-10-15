package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_user} — the customer whose subscription fee is being reconciled. */
@Data
@Entity
@Table(name = "tbl_user", catalog = DBConstants.YESMADAM_USERS)
public class EliteMismatchSyncUser {

  @Id
  @Column(name = "user_id")
  private Long userId;

  private String name;

  /** {@code java.util.Date}, matching tracking's own {@code User#dob} exactly — its {@code toString()} is used verbatim as-is. */
  private Date dob;
}
