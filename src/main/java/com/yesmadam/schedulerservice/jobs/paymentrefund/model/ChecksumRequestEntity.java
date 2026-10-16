package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

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
 * Local mapping of {@code tbl_checksum_request}, scoped to the columns this job reads and writes.
 *
 * <p>The shared {@code ym-common-entity} jar declares a same-named entity for this table, but it
 * (checked through version 1.12.7) has no {@code orderCreated}, {@code successAt} or {@code
 * createdFrom} columns — exactly the ones this job's read filter and write path depend on — so it
 * is not reused here. See the payment-refund migration plan for the full comparison.
 */
@Data
@Entity
@Table(name = "tbl_checksum_request", catalog = DBConstants.YESMADAM_USERS)
public class ChecksumRequestEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "userId")
  private Long userId;

  @Column(name = "orderId")
  private Long orderId;

  @Column(name = "orderRequestText", columnDefinition = "text")
  private String orderRequestText;

  @Column(name = "payingFor")
  private String payingFor;

  @Column(name = "paymentGateway")
  private String paymentGateway;

  @Column(name = "successAt")
  private Timestamp successAt;

  @Column(name = "orderCreated")
  private boolean orderCreated;

  @Column(name = "createdFrom")
  private String createdFrom;
}
