package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

import java.sql.Timestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * One row per distribution round for an order, in {@code tbl_job_distribution}.
 *
 * <p><b>{@code is_active} carries the "one live round per order" guarantee.</b> It is {@code 1}
 * while the round is live and {@code NULL} once closed, and a {@code UNIQUE KEY (order_id,
 * is_active)} backs it: MySQL treats NULLs as distinct in unique indexes, so unlimited closed rows
 * are allowed but only one live row can exist per order. Any code that closes a round must set this
 * to NULL, not to 0 — setting 0 would make the unique key reject the next round.
 *
 * <p>Field names are snake_case to match the columns exactly, as in the original entity; the
 * repository's projections use camelCase aliases instead.
 */
@Data
@Entity
@Table(name = "tbl_job_distribution", catalog = DBConstants.YESMADAM_USERS)
public class JobDistribution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id")
  private Long orderId;

  @Column(name = "round_number")
  private Integer roundNumber;

  /** One of {@link JobDistributionStatus}, stored as its name. */
  private String status;

  @Column(name = "incentive_amount")
  private Integer incentiveAmount;

  @Column(name = "sp_notified_count")
  private Integer spNotifiedCount;

  @Column(name = "accepted_sp_id")
  private Integer acceptedSpId;

  @Column(name = "accepted_incentive")
  private Integer acceptedIncentive;

  @Column(name = "scheduled_at")
  private Timestamp scheduledAt;

  /** {@code 1} while live, {@code NULL} once closed — see the class note. */
  @Column(name = "is_active")
  private Integer isActive;

  @Column(name = "created_at")
  private Timestamp createdAt;

  @Column(name = "updated_at")
  private Timestamp updatedAt;
}
