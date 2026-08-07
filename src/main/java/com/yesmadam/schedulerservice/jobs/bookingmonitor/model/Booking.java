package com.yesmadam.schedulerservice.jobs.bookingmonitor.model;

import java.sql.Timestamp;

import javax.persistence.*;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

@Data
@Entity
@Table(name = "tbl_order", catalog = DBConstants.YSMDM_USERS)
public class Booking {

  @Id
  @Column(name = "order_id")
  private Long orderId;

  @Column(name = "order_from")
  private String orderFrom;

  @Column(name = "created_at")
  private Timestamp createdAt;
}
