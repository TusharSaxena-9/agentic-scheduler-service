package com.yesmadam.schedulerservice.jobs.spNotReachedHome.model;

import java.sql.Date;
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
 * {@code tbl_sp_not_reached_home} — one row per SP per day, keyed by the {@code uq_sp_tracking_date
 * (sp_id, tracking_date)} unique constraint that makes the tracker's UPSERT idempotent.
 *
 * <p>Present so the repository has a managed entity to anchor to; the job itself reads through
 * projections and writes through {@link
 * com.yesmadam.schedulerservice.jobs.spNotReachedHome.repository.SpNotReachedHomeBatchDao}
 * multi-row statements, because there is no JPA way to express {@code ON DUPLICATE KEY UPDATE}.
 */
@Data
@Entity
@Table(name = "tbl_sp_not_reached_home", catalog = DBConstants.YESMADAM_ADMIN)
public class SpNotReachedHome {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "sp_id")
  private Integer spId;

  @Column(name = "sp_name")
  private String spName;

  @Column(name = "last_order_id")
  private Long lastOrderId;

  @Column(name = "last_order_end_time")
  private Timestamp lastOrderEndTime;

  @Column(name = "current_lat")
  private Double currentLat;

  @Column(name = "current_lon")
  private Double currentLon;

  @Column(name = "home_lat")
  private String homeLat;

  @Column(name = "home_lon")
  private String homeLon;

  @Column(name = "distance_from_home_mtr")
  private Double distanceFromHomeMtr;

  /** {@code 0} = still out, {@code 1} = confirmed home. */
  @Column(name = "reached_home")
  private Integer reachedHome;

  /** {@code SYSTEM} when the tracker marked it, otherwise the operator's username. */
  @Column(name = "operated_by")
  private String operatedBy;

  @Column(name = "tracking_date")
  private Date trackingDate;
}
