package com.yesmadam.scheduler_service.packageexpiry.model;

import java.sql.Timestamp;

import javax.persistence.*;

import com.yesmadam.scheduler_service.common.constants.DBConstants;

import lombok.Data;

@Data
@Entity
@Table(name = "tbl_multisession_reports", catalog = DBConstants.YESMADAM_ADMIN)
public class MultiSessionReports {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "transaction_id")
  private Long transactionId;

  @Column(name = "order_id")
  private Long orderId;

  @Column(name = "user_id")
  private Long userId;

  @Column(name = "session_id")
  private Integer sessionId;

  @Column(name = "package_id")
  private Integer packageId;

  @Column(name = "cost_head")
  private String costHead;

  @Column(name = "amount")
  private Integer amount;

  @Column(name = "transaction_type")
  private String transactionType;

  @Column(name = "created_at")
  private Timestamp createdAt;
}
