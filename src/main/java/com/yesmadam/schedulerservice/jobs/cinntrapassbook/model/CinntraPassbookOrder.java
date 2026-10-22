package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/** Minimal projection of {@code ysmdm_users.tbl_order}: only the category, for the costing code. */
@Data
@Entity
@Table(name = "tbl_order", catalog = DBConstants.YESMADAM_USERS)
public class CinntraPassbookOrder {

  @Id
  @Column(name = "order_id")
  private Long orderId;

  @Column(name = "category_id")
  private Integer categoryId;
}
