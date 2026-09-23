package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yesmadam.persistence.OrderRefunded;
import com.yesmadam.persistence.PaytmStatusResponse;
import com.yesmadam.persistence.User;
import com.yesmadam.persistence.UserOTP;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.AppServerApiResponse;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.ChecksumRequestEntity;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.CreateOrderObject;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.OtpVerificationRequest;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundRowOutcome;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundRowResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaytmRefundResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuRefundResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuTransactionsEntity;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuVerifyResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.ChecksumRequestRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundOrderRefundedRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundOrderRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundPaytmStatusRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundPayuTransactionsRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundUserOtpRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundUserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Ports the per-row body of {@code PaymentRefundComponent.validateOrderCreationOnPayment} from
 * {@code shortlink}. One call here handles exactly one {@code tbl_checksum_request} row, so a
 * Temporal retry of one problem row never re-touches rows that already succeeded.
 *
 * <p><b>Known gap, carried over unchanged from the source:</b> both refund paths ({@link
 * #attemptPaytmRefund}, {@link #attemptPayuRefund}) call the gateway's refund API and only
 * afterward persist the {@code tbl_order_refunded} row that {@link #processRow}'s own idempotency
 * check reads. If the activity is killed after the refund call succeeds but before that row
 * commits, a retry re-checks {@code existsByTransferId}, still finds nothing, and calls the
 * gateway's refund API a second time for the same transaction. {@code shortlink}'s original cron
 * has this exact same exposure today (a crash mid-run is picked up by the next tick the same way).
 * Closing it needs a pre-call idempotency marker (CLAUDE.md rule 14) and is left as a follow-up
 * rather than risked here unverified against a real Paytm/PayU sandbox.
 */
@Slf4j
@Service
public class PaymentRefundOrderCreationService {

  private static final List<String> PREPAID_LIKE_PAYMENT_SOURCES =
      List.of("prepaid", "paytm", "upi", "payu_prepaid", "payuupi", "phonepe", "gpay", "payu");

  private final ChecksumRequestRepository checksumRequestRepository;
  private final PaymentRefundOrderRepository orderRepository;
  private final PaymentRefundOrderRefundedRepository orderRefundedRepository;
  private final PaymentRefundUserRepository userRepository;
  private final PaymentRefundUserOtpRepository userOtpRepository;
  private final PaymentRefundPaytmStatusRepository paytmStatusRepository;
  private final PaymentRefundPayuTransactionsRepository payuTransactionsRepository;
  private final AppServerOrderClient appServerOrderClient;
  private final PaytmVerificationClient paytmVerificationClient;
  private final PayuVerificationClient payuVerificationClient;
  private final KarixSmsClient karixSmsClient;
  private final PaymentRefundWhatsappClient whatsappClient;
  private final ChecksumRowWriter checksumRowWriter;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public PaymentRefundOrderCreationService(
      ChecksumRequestRepository checksumRequestRepository,
      PaymentRefundOrderRepository orderRepository,
      PaymentRefundOrderRefundedRepository orderRefundedRepository,
      PaymentRefundUserRepository userRepository,
      PaymentRefundUserOtpRepository userOtpRepository,
      PaymentRefundPaytmStatusRepository paytmStatusRepository,
      PaymentRefundPayuTransactionsRepository payuTransactionsRepository,
      AppServerOrderClient appServerOrderClient,
      PaytmVerificationClient paytmVerificationClient,
      PayuVerificationClient payuVerificationClient,
      KarixSmsClient karixSmsClient,
      PaymentRefundWhatsappClient whatsappClient,
      ChecksumRowWriter checksumRowWriter) {
    this.checksumRequestRepository = checksumRequestRepository;
    this.orderRepository = orderRepository;
    this.orderRefundedRepository = orderRefundedRepository;
    this.userRepository = userRepository;
    this.userOtpRepository = userOtpRepository;
    this.paytmStatusRepository = paytmStatusRepository;
    this.payuTransactionsRepository = payuTransactionsRepository;
    this.appServerOrderClient = appServerOrderClient;
    this.paytmVerificationClient = paytmVerificationClient;
    this.payuVerificationClient = payuVerificationClient;
    this.karixSmsClient = karixSmsClient;
    this.whatsappClient = whatsappClient;
    this.checksumRowWriter = checksumRowWriter;
  }

  /** Read-only: the candidate row ids for the given window. Bounded (CLAUDE.md rule 9). */
  @Transactional(readOnly = true)
  public List<Long> findPendingChecksumIds(Timestamp windowStart, Timestamp windowEnd) {
    return checksumRequestRepository
        .findAllByOrderCreatedAndSuccessAtBetweenAndPayingFor(
            false, windowStart, windowEnd, "Service")
        .stream()
        .map(ChecksumRequestEntity::getId)
        .toList();
  }

  public PaymentRefundRowResult processRow(Long checksumId) {
    ChecksumRequestEntity row = checksumRequestRepository.findById(checksumId).orElse(null);
    if (row == null) {
      return new PaymentRefundRowResult(
          checksumId, PaymentRefundRowOutcome.ALREADY_HANDLED, "row no longer exists");
    }
    if (!"Service".equalsIgnoreCase(row.getPayingFor())) {
      // The source's non-"Service" branch (subscription/Elite) is dead code today (its real logic
      // is commented out and orderCreated is force-set true). Rather than port a no-op, skip and
      // leave the row for a future job to handle once that path is actually implemented.
      return new PaymentRefundRowResult(
          checksumId, PaymentRefundRowOutcome.UNSUPPORTED_PAYING_FOR, row.getPayingFor());
    }
    if (row.getOrderId() == null) {
      return new PaymentRefundRowResult(
          checksumId, PaymentRefundRowOutcome.FAILED, "missing orderId");
    }
    String paymentSourceId = row.getOrderId().toString();

    if (orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(
            paymentSourceId, PREPAID_LIKE_PAYMENT_SOURCES)
        || orderRefundedRepository.existsByTransferId(paymentSourceId)) {
      return new PaymentRefundRowResult(
          checksumId, PaymentRefundRowOutcome.ALREADY_HANDLED, "order or refund already recorded");
    }

    try {
      String gateway = row.getPaymentGateway();
      if ("paytm".equalsIgnoreCase(gateway)) {
        return processPaytmRow(row, paymentSourceId);
      } else if ("payu".equalsIgnoreCase(gateway)) {
        return processPayuRow(row, paymentSourceId);
      }
      return new PaymentRefundRowResult(
          checksumId, PaymentRefundRowOutcome.FAILED, "unhandled paymentGateway " + gateway);
    } catch (Exception e) {
      log.error(
          "event=payment_refund_row_failed checksum_id={} error={}", checksumId, e.getMessage(), e);
      return new PaymentRefundRowResult(checksumId, PaymentRefundRowOutcome.FAILED, e.getMessage());
    }
  }

  /**
   * Paytm branch: a confirmed TXN_SUCCESS with no resulting order always means a refund here — the
   * source never attempts order creation for Paytm, only for PayU. Preserved as-is even though that
   * asymmetry looks like an oversight, since fixing it is a behavior change out of scope for a
   * migration.
   *
   * <p>Mirrors the source's two-part structure exactly: the validate-response save (below) is
   * unprotected — if it throws, this row is left unmarked and retried next tick, same as source.
   * Everything from {@link #attemptPaytmRefund} onward corresponds to {@code
   * Utilities.getOrderRefundedForNoOrder}, which swallows every exception internally and never
   * propagates — so the checksum row is still marked handled afterward there too (matching the
   * source, which never inspects {@code getOrderRefundedForNoOrder}'s return value), but the
   * outcome reported to the caller reflects whether the attempt actually produced a result: {@code
   * attemptPaytmRefund} returning {@code null} (its own catch block fired) is reported as {@code
   * FAILED}, not {@code REFUND_ISSUED} — the DB write being source-faithful is not a reason for the
   * workflow's own summary counters to say something happened when it didn't.
   */
  private PaymentRefundRowResult processPaytmRow(
      ChecksumRequestEntity row, String paymentSourceId) {
    PaytmStatusResponse status = paytmVerificationClient.validateTransaction(paymentSourceId);
    if (status == null) {
      return new PaymentRefundRowResult(
          row.getId(), PaymentRefundRowOutcome.FAILED, "paytm validate call failed");
    }
    if (!"TXN_SUCCESS".equalsIgnoreCase(status.getSTATUS())) {
      return new PaymentRefundRowResult(
          row.getId(), PaymentRefundRowOutcome.NOT_SUCCESSFUL_YET, status.getSTATUS());
    }

    status.setCREATED_AT(new Timestamp(System.currentTimeMillis()));
    paytmStatusRepository.save(status);

    String resultStatus = attemptPaytmRefund(row, paymentSourceId, status);
    checksumRowWriter.markHandled(row.getId(), "Refund From Scheduler");
    PaymentRefundRowOutcome outcome =
        resultStatus == null ? PaymentRefundRowOutcome.FAILED : PaymentRefundRowOutcome.REFUND_ISSUED;
    return new PaymentRefundRowResult(row.getId(), outcome, resultStatus);
  }

  /**
   * Ports {@code Utilities.getOrderRefundedForNoOrder}'s Paytm branch, including the second {@code
   * tbl_paytm_payment_status} row it saves for the refund-call result (distinct from the
   * validate-call snapshot already saved by the caller) and the refund-notice SMS. Catches and
   * swallows everything, matching the source's own {@code catch (Exception exception) { ...; return
   * false; }} — the caller marks the row handled regardless of what happens here, but a {@code null}
   * return here is what tells it the attempt did not actually complete.
   */
  private String attemptPaytmRefund(
      ChecksumRequestEntity row, String paymentSourceId, PaytmStatusResponse status) {
    try {
      PaytmRefundResult refund =
          paytmVerificationClient.refund(paymentSourceId, status.getTXNID(), status.getTXNAMOUNT());

      PaytmStatusResponse refundSnapshot = new PaytmStatusResponse();
      BeanUtils.copyProperties(status, refundSnapshot);
      refundSnapshot.setID(null);
      refundSnapshot.setSTATUS(refund.getResultStatus());
      refundSnapshot.setREFUNDAMT(refund.getRefundAmount());
      refundSnapshot.setCREATED_AT(new Timestamp(System.currentTimeMillis()));
      paytmStatusRepository.save(refundSnapshot);

      if (refund.isRefunded()) {
        notifyRefundOutcome(row.getUserId(), true, status.getTXNAMOUNT());
      } else {
        notifyRefundOutcome(row.getUserId(), false, null);
      }
      // txnAmount is the originally-requested amount (TXNAMOUNT), not the refund response's
      // figure — matches refundRequestForShedular.getRefundAmount() in the source, which was set
      // from paytmUserStatusResponse.getTXNAMOUNT() before this call, not from the refund result.
      saveOrderRefunded(
          row,
          paymentSourceId,
          status.getTXNID(),
          refund.getResultStatus(),
          refund.isRefunded(),
          roundToWholeRupees(status.getTXNAMOUNT()),
          "Refund from shedular");
      return refund.getResultStatus();
    } catch (Exception e) {
      log.error(
          "event=paytm_refund_attempt_failed checksum_id={} error={}",
          row.getId(),
          e.getMessage(),
          e);
      notifyRefundOutcome(row.getUserId(), false, null);
      return null;
    }
  }

  /**
   * PayU branch: on a confirmed success, attempt order creation first (via the OTP-bypass +
   * create-order calls); only if that fails does it fall back to a refund, re-checking that no
   * order appeared in the meantime.
   */
  @SuppressWarnings("unchecked")
  private PaymentRefundRowResult processPayuRow(ChecksumRequestEntity row, String paymentSourceId) {
    PayuVerifyResult verify = payuVerificationClient.verifyPayment(paymentSourceId);
    if (verify == null) {
      return new PaymentRefundRowResult(
          row.getId(), PaymentRefundRowOutcome.FAILED, "payu verify call failed");
    }
    if (!"success".equalsIgnoreCase(verify.getStatus())) {
      return new PaymentRefundRowResult(
          row.getId(), PaymentRefundRowOutcome.NOT_SUCCESSFUL_YET, verify.getStatus());
    }

    AppServerApiResponse createOrderResponse = null;
    boolean createOrderCallAttempted = false;
    try {
      Map<String, Object> orderRequest =
          objectMapper.readValue(row.getOrderRequestText(), Map.class);
      orderRequest.put("payment_source_id", paymentSourceId);

      String bearerToken = verifyOtpAndGetBearerToken(row.getUserId());
      if (bearerToken != null) {
        createOrderCallAttempted = true;
        createOrderResponse = appServerOrderClient.createOrder(orderRequest, bearerToken);
      }
    } catch (Exception e) {
      log.error(
          "event=payu_order_creation_attempt_failed checksum_id={} error={}",
          row.getId(),
          e.getMessage());
    }

    boolean orderCreated =
        createOrderResponse != null && "success".equalsIgnoreCase(createOrderResponse.getStatus());
    if (orderCreated) {
      checksumRowWriter.markHandled(row.getId(), "Order Created From Scheduler");
      notifyBookingCreated(row.getUserId(), createOrderResponse.getObject());
      return new PaymentRefundRowResult(row.getId(), PaymentRefundRowOutcome.ORDER_CREATED, null);
    }

    if (createOrderCallAttempted && createOrderResponse == null) {
      // The create-order HTTP call itself didn't complete (timeout/IO error) — AppServerOrderClient
      // caught the exception and returned null, meaning we genuinely don't know app-server's
      // disposition. Its own request keeps running server-side after our client gives up waiting
      // (seen in UAT: a create-order call outlasting our read timeout, with the order actually
      // landing moments later on app-server's side). Refunding now risks clawing back money for a
      // transaction that's about to become a fulfilled order. Leave the row unmarked rather than
      // falling back to a refund — the next tick's existsByPaymentSourceIdAndPaymentSourceIn check
      // resolves this cleanly either way once app-server's request has actually finished.
      log.error(
          "event=payu_create_order_inconclusive checksum_id={} payment_source_id={}",
          row.getId(),
          paymentSourceId);
      return new PaymentRefundRowResult(
          row.getId(),
          PaymentRefundRowOutcome.FAILED,
          "create-order call did not complete (timeout/IO error); deferring to next tick before refunding");
    }

    return fallBackToPayuRefund(row, paymentSourceId, verify);
  }

  /**
   * WhatsApp Case 2. {@code doid} (not the transaction id) is what create-order's own response
   * shows the customer, so that's what goes in the template — same for {@code booking_date} and
   * the button's URL suffix ({@code oid}), all read straight from the response rather than guessed
   * at from the stored request payload.
   */
  private void notifyBookingCreated(Long userId, CreateOrderObject createOrderObject) {
    try {
      User customer = userRepository.findById(userId).orElse(null);
      if (customer == null) {
        return;
      }
      String orderId = createOrderObject == null ? null : createOrderObject.getDoid();
      String scheduledDate = createOrderObject == null ? null : createOrderObject.getBookingDate();
      String buttonSuffix = createOrderObject == null ? null : createOrderObject.getOid();
      whatsappClient.sendBookingCreated(
          String.valueOf(customer.getMobile()), customer.getName(), orderId, scheduledDate, buttonSuffix);
    } catch (Exception e) {
      log.error(
          "event=booking_created_whatsapp_lookup_failed user_id={} error={}", userId, e.getMessage());
    }
  }

  /**
   * Mirrors the source exactly: the {@code existing} row update+save below is unprotected (an
   * outer {@code catch} in the source aborts the whole block without marking the row if this
   * throws), while everything inside {@link #attemptPayuRefund} corresponds to {@code
   * Utilities.getOrderRefundedForNoOrder} — swallowed internally, never propagated — so the row is
   * still marked handled once that call returns, matching the source's unconditional {@code
   * existingCheckSumRecord.setOrderCreated(true)} after it. As with the Paytm branch, the outcome
   * returned to the caller is truthful even though the DB write is not: {@code attemptPayuRefund}
   * returning {@code null} is reported as {@code FAILED}.
   *
   * <p>The {@code existsByTransferId} re-check below is not source-derived — it closes a real gap
   * found after this job was in UAT: app-server's {@code createOrder} has ~12 validation branches
   * (slot expired, slot unavailable, package already completed, ...) that, on failure, call their
   * own {@code getOrderRefundedOnError} inline before returning FAILED — writing the exact same
   * {@code tbl_order_refunded.transferId = payment_source_id} row this job reads, via the shared
   * {@code ym-common-entity} {@code OrderRefunded} entity. {@link #processRow}'s idempotency check
   * runs once, before the {@code createOrder} call this row's caller just made — so by the time
   * this method runs, app-server may have already refunded the transaction as a side effect of
   * that very call. Without re-checking here, this job would call PayU's refund API a second time
   * for a transaction app-server just refunded, which PayU correctly rejects (seen in UAT as
   * "Invalid amount" — nothing left to refund) — and the customer got the wrong WhatsApp template
   * for it (Case 3 "pending" instead of nothing, since app-server's own refund already sent its
   * own Karix SMS). This does not need a new database write to close: the check app-server itself
   * just performed is already visible here on the same table.
   */
  private PaymentRefundRowResult fallBackToPayuRefund(
      ChecksumRequestEntity row, String paymentSourceId, PayuVerifyResult verify) {
    boolean stillNoOrder =
        !orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(
            paymentSourceId, PREPAID_LIKE_PAYMENT_SOURCES);
    if (!stillNoOrder) {
      return new PaymentRefundRowResult(
          row.getId(), PaymentRefundRowOutcome.ALREADY_HANDLED, "order appeared during processing");
    }
    if (orderRefundedRepository.existsByTransferId(paymentSourceId)) {
      // app-server's own createOrder call (the one processPayuRow just made) already refunded
      // this transaction as a side effect of rejecting the order — do not refund it again.
      checksumRowWriter.markHandled(row.getId(), "Refund From Scheduler");
      return new PaymentRefundRowResult(
          row.getId(),
          PaymentRefundRowOutcome.ALREADY_HANDLED,
          "app-server already refunded this transaction during create-order");
    }

    PayuTransactionsEntity existing =
        payuTransactionsRepository.findFirstByOrderIdOrderByIdDesc(paymentSourceId);
    if (existing == null) {
      return new PaymentRefundRowResult(
          row.getId(),
          PaymentRefundRowOutcome.FAILED,
          "no PayuTransactions row for " + paymentSourceId);
    }
    existing.setOrderId(paymentSourceId);
    existing.setUSER_ID(row.getUserId());
    existing.setTxnData(verify.getRawJson());
    existing.setStatus(verify.getStatus());
    existing.setAddedon(verify.getAddedOn());
    existing.setMihpayid(verify.getMihpayid());
    existing.setTransaction_amount(verify.getTransactionAmount());
    existing.setMode(verify.getMode());
    existing.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
    payuTransactionsRepository.save(existing);

    String resultStatus = attemptPayuRefund(row, paymentSourceId, existing, verify);
    checksumRowWriter.markHandled(row.getId(), "Refund From Scheduler");
    PaymentRefundRowOutcome outcome =
        resultStatus == null ? PaymentRefundRowOutcome.FAILED : PaymentRefundRowOutcome.REFUND_ISSUED;
    return new PaymentRefundRowResult(row.getId(), outcome, resultStatus);
  }

  /**
   * Ports {@code Utilities.getOrderRefundedForNoOrder}'s PayU branch: the refund-copy audit row
   * (full {@link BeanUtils#copyProperties} of {@code existing}, matching the source rather than a
   * hand-picked field subset), the refund-notice SMS, and the {@code tbl_order_refunded} write.
   * Catches and swallows everything, matching the source.
   *
   * <p>The amount PayU is asked to refund is parsed into a {@link BigDecimal} and — critically —
   * the {@link PayuVerificationClient#refund} call is given the exact original decimal string, not
   * a value rounded through a {@code float}/{@code int}. Rounding before sending is what causes
   * PayU to reject the request (a rounded-up amount can exceed what's actually refundable) or
   * silently short-change the customer by the rounded-off paise. If the amount can't be parsed at
   * all (blank, non-numeric), the PayU call is never made — an unparseable amount is treated as an
   * immediate failure rather than sent to the gateway as a guess.
   */
  private String attemptPayuRefund(
      ChecksumRequestEntity row,
      String paymentSourceId,
      PayuTransactionsEntity existing,
      PayuVerifyResult verify) {
    try {
      String rawAmount = verify.getTransactionAmount();
      BigDecimal amount = new BigDecimal(rawAmount);

      PayuRefundResult refund = payuVerificationClient.refund(verify.getMihpayid(), rawAmount);

      PayuTransactionsEntity refundCopy = new PayuTransactionsEntity();
      BeanUtils.copyProperties(existing, refundCopy);
      refundCopy.setId(null);
      refundCopy.setRefundStatus(refund.getResultStatus());
      refundCopy.setRefund_amount(rawAmount);
      // Same uniqueId the outbound PayU refund call used (var2) — not a fresh timestamp, or this
      // audit row could never be correlated back to the actual request PayU received.
      refundCopy.setRefundUniqueId(String.valueOf(refund.getUniqueId()));
      refundCopy.setRefund_request_id(refund.getRequestId());
      refundCopy.setRefundCreatedAt(new Timestamp(System.currentTimeMillis()));
      refundCopy.setFlagForError(true);
      payuTransactionsRepository.save(refundCopy);

      if (refund.isRefunded()) {
        notifyRefundOutcome(row.getUserId(), true, rawAmount);
      } else {
        notifyRefundOutcome(row.getUserId(), false, null);
      }
      saveOrderRefunded(
          row,
          paymentSourceId,
          verify.getMihpayid(),
          refund.getResultStatus(),
          refund.isRefunded(),
          roundToWholeRupees(amount),
          "Refund from shedular");
      return refund.getResultStatus();
    } catch (Exception e) {
      log.error(
          "event=payu_refund_attempt_failed checksum_id={} error={}",
          row.getId(),
          e.getMessage(),
          e);
      notifyRefundOutcome(row.getUserId(), false, null);
      return null;
    }
  }

  /**
   * Ports the {@code if (refundOne.getRefundedcheck())} SMS block shared by both gateway branches
   * in {@code Utilities.getOrderRefundedForNoOrder} (never throws — a failed lookup or send must
   * not fail the refund itself), and additionally fires the new WhatsApp Case 1/Case 3 templates
   * alongside it: {@code refunded=true} sends the SMS plus "payment_refund_initiated_1" (Case 1);
   * {@code refunded=false} — including the attempt throwing, per the catch blocks above — sends
   * only "payment_refund_pending" (Case 3). WhatsApp is new capability added on top of the ported
   * source behavior, not a replacement for it.
   */
  private void notifyRefundOutcome(Long userId, boolean refunded, String refundAmount) {
    try {
      User customer = userRepository.findById(userId).orElse(null);
      if (customer == null) {
        return;
      }
      String mobile = String.valueOf(customer.getMobile());
      if (refunded) {
        karixSmsClient.sendRefundNotice(customer.getMobile());
        whatsappClient.sendRefundInitiated(mobile, customer.getName(), refundAmount);
      } else {
        whatsappClient.sendRefundPending(mobile, customer.getName());
      }
    } catch (Exception e) {
      log.error("event=refund_notification_lookup_failed user_id={} error={}", userId, e.getMessage());
    }
  }

  private String verifyOtpAndGetBearerToken(Long userId) {
    User customer = userRepository.findById(userId).orElse(null);
    if (customer == null || customer.getMobile() == null) {
      log.error("event=payment_refund_customer_not_found user_id={}", userId);
      return null;
    }

    UserOTP userOtp = new UserOTP();
    userOtp.setMobile(customer.getMobile());
    userOtp.setMobile_otp(Integer.parseInt(randomNumericString(4)));
    Timestamp now = new Timestamp(System.currentTimeMillis());
    userOtp.setCreated_at(now);
    userOtp.setM_otp_expire_time(new Timestamp(now.getTime() + (30L * 60 * 1000)));
    userOtpRepository.save(userOtp);

    return appServerOrderClient.verifyOtpAndGetBearerToken(
        new OtpVerificationRequest(customer.getMobile(), userOtp.getMobile_otp()));
  }

  private void saveOrderRefunded(
      ChecksumRequestEntity row,
      String transferId,
      String txnId,
      String status,
      boolean refunded,
      Long txnAmount,
      String remark) {
    OrderRefunded refundRow = new OrderRefunded();
    refundRow.setApproved_by("AUTO");
    refundRow.setStatus(status);
    refundRow.setIs_refunded(refunded);
    refundRow.setTransferId(transferId);
    refundRow.setTxn_id(txnId);
    refundRow.setUserId(row.getUserId());
    refundRow.setRemark(remark);
    refundRow.setTxnAmount(txnAmount);
    refundRow.setCreatedAt(new Timestamp(System.currentTimeMillis()));
    orderRefundedRepository.save(refundRow);
  }

  /**
   * {@code tbl_order_refunded.txnAmount} is a {@code Long} column, so some rounding to whole
   * rupees is unavoidable here — unlike the amount sent to PayU's refund API, which must stay the
   * exact string PayU reported (see {@link #attemptPayuRefund}). Uses {@link BigDecimal} rather
   * than {@code float}/{@code Math.round}, which loses precision past ~7 significant digits and
   * rounds via binary-float approximation rather than decimal rules.
   */
  private static Long roundToWholeRupees(String amount) {
    if (amount == null) {
      return 0L;
    }
    try {
      return roundToWholeRupees(new BigDecimal(amount));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static Long roundToWholeRupees(BigDecimal amount) {
    return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private static String randomNumericString(int length) {
    String digits = "123456789";
    StringBuilder sb = new StringBuilder(length);
    Random random = new Random();
    for (int i = 0; i < length; i++) {
      sb.append(digits.charAt(random.nextInt(digits.length())));
    }
    return sb.toString();
  }
}
