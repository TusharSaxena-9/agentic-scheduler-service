package com.yesmadam.schedulerservice.jobs.paymentrefund.model;

import java.sql.Timestamp;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

import lombok.Data;

/**
 * Local mapping of {@code tbl_payu_transaction_details}, mirroring shortlink's own local {@code
 * PayuTransactions} entity field-for-field (field names included, so the generated accessors
 * match shortlink's exactly, e.g. {@code getUSER_ID()}/{@code setTransaction_amount(...)}).
 *
 * <p>The shared {@code ym-common-entity} jar does declare a matching entity, {@code
 * com.yesmadam.wellness.persistence.PayuTransactions}, but it sits outside this service's {@code
 * @EntityScan({"com.yesmadam.schedulerservice", "com.yesmadam.persistence"})} on {@code
 * SchedulerServiceApplication} (note the extra {@code wellness} package segment), so Hibernate
 * rejects it as "not a managed type" at startup. Defining it locally here avoids widening that
 * shared, service-wide annotation for one job.
 */
@Data
@Entity
@Table(name = "tbl_payu_transaction_details", catalog = DBConstants.YESMADAM_USERS)
public class PayuTransactionsEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String orderId;
  private String txnData;
  private String status;
  private Long USER_ID;
  private String addedon;
  private String mihpayid;
  private String transaction_amount;
  private String mode;
  private String refund_amount;
  private String refundUniqueId;
  private String refund_request_id;
  private String refundStatus;
  private Timestamp createdAt;
  private Timestamp updatedAt;
  private Timestamp refundCreatedAt;
  private Timestamp refundUpdatedAt;
  private Boolean flagForError;
}
