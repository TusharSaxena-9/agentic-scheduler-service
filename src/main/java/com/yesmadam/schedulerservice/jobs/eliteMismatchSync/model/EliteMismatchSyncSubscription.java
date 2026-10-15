package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import java.sql.Timestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code tbl_customer_subscriptions_elite} — just the fields this job reads. */
@Data
@Entity
@Table(name = "tbl_customer_subscriptions_elite", catalog = DBConstants.YESMADAM_USERS)
public class EliteMismatchSyncSubscription {

  @Id private Integer id;

  @Column(name = "customerId")
  private Long customerId;

  @Column(name = "subscriptionId")
  private Integer subscriptionId;

  @Column(name = "createdAt")
  private Timestamp createdAt;

  @Column(name = "discounted_fee")
  private Integer discountedFee;

  @Column(name = "txn_id")
  private String txnid;
}
