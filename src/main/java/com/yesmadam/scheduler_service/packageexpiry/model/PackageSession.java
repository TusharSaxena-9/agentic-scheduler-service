package com.yesmadam.scheduler_service.packageexpiry.model;

import java.sql.Timestamp;

import javax.persistence.*;

import com.yesmadam.scheduler_service.common.constants.DBConstants;

import lombok.Data;

@Data
@Entity
@Table(name = "tbl_packages", catalog = DBConstants.YESMADAM_USERS)
public class PackageSession {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "package_purchase_id")
  private Integer packagePurchaseId;

  @Column(name = "transport_fee")
  private Integer transportFee;

  @Column(name = "conv_fee")
  private Double convFee;

  @Column(name = "disposable_fee")
  private Double disposableFee;

  @Column(name = "remaining_session_count")
  private Integer remainingSessionCount;

  @Column(name = "status")
  private Integer status;

  @Column(name = "scheduled_on")
  private Timestamp scheduledOn;

  @Column(name = "expires_on")
  private Timestamp expiresOn;

  @Column(name = "user_id")
  private Long userId;

  @Column(name = "created_at")
  private Timestamp createdAt;

  @Column(name = "updated_at")
  private Timestamp updatedAt;

  @Column(name = "address_id")
  private Long addressId;

  @Column(name = "quantity")
  private Integer quantity;

  @Column(name = "no_of_sessions")
  private Integer noOfSessions;

  @Column(name = "service_id")
  private Integer serviceId;

  @Column(name = "category_id")
  private Integer categoryId;

  @Column(name = "is_scheduled")
  private Boolean isScheduled;

  @Column(name = "refund_unique_id")
  private String refundUniqueId;

  @Column(name = "refund_request_id")
  private String refundRequestId;

  @Column(name = "canceled_at")
  private Timestamp canceledAt;

  @Column(name = "refund_amount")
  private Integer refundAmount;

  @Column(name = "cancellation_reason")
  private String cancellationReason;
}
