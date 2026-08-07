package com.yesmadam.schedulerservice.jobs.crontiming.model;

import java.sql.Timestamp;

import javax.persistence.*;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * Entity for tracking cron job execution start and end times. The android, ios, start and end
 * fields are optional and only populated by workflows that track platform-specific booking counts.
 */
@Data
@Entity
@Table(name = "tbl_cron_time", catalog = DBConstants.YESMADAM_ADMIN)
public class CronTiming {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String javaFileName;
  private String methodName;
  private Timestamp cronStartAt;
  private Timestamp cronEndAt;
  private Timestamp createdAt;
  private String remark;

  /** Booking window start time — populated by booking monitor workflow only */
  private Timestamp start;

  /** Booking window end time — populated by booking monitor workflow only */
  private Timestamp end;

  /** Android booking count for the window — populated by booking monitor workflow only */
  private Integer android;

  /** iOS booking count for the window — populated by booking monitor workflow only */
  private Integer ios;
}
