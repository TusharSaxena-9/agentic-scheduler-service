package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import java.sql.Timestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * Minimal projection of {@code tbl_order} — used only for the elite-fee lookup ({@code
 * getOrdersWithEliteFees} in tracking), which filters by {@code userid} (the customer) and a
 * non-null {@code elite_fees}.
 */
@Data
@Entity
@Table(name = "tbl_order", catalog = DBConstants.YESMADAM_USERS)
public class EliteMismatchSyncOrder {

  @Id
  @Column(name = "order_id")
  private Long id;

  @Column(name = "user_id")
  private Integer userid;

  @Column(name = "elite_fees")
  private Integer eliteFees;

  @Column(name = "customerSubscriptionId")
  private Integer customerSubscriptionId;

  @Column(name = "created_at")
  private Timestamp createdAt;
}
