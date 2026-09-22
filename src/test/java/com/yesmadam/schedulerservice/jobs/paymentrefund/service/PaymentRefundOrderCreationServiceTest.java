package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.persistence.PaytmStatusResponse;
import com.yesmadam.persistence.User;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.ChecksumRequestEntity;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundRowOutcome;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundRowResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaytmRefundResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuRefundResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuVerifyResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.ChecksumRequestRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundOrderRefundedRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundOrderRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundPaytmStatusRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundPayuTransactionsRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundUserOtpRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.PaymentRefundUserRepository;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuTransactionsEntity;

/**
 * CLAUDE.md rule 12: covers the service layer's branches, including the already-handled
 * short-circuits, both gateways' success/not-yet/failure paths, and — the two branches most worth
 * pinning down after review — that a swallowed refund-attempt failure is reported as {@code
 * FAILED} rather than a false {@code REFUND_ISSUED}, and that PayU's refund amount reaches the
 * gateway exactly as PayU reported it, never rounded through a float.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRefundOrderCreationServiceTest {

  private static final Long CHECKSUM_ID = 1L;
  private static final Long USER_ID = 42L;
  private static final Long ORDER_ID = 999L;
  private static final String PAYMENT_SOURCE_ID = "999";

  @Mock ChecksumRequestRepository checksumRequestRepository;
  @Mock PaymentRefundOrderRepository orderRepository;
  @Mock PaymentRefundOrderRefundedRepository orderRefundedRepository;
  @Mock PaymentRefundUserRepository userRepository;
  @Mock PaymentRefundUserOtpRepository userOtpRepository;
  @Mock PaymentRefundPaytmStatusRepository paytmStatusRepository;
  @Mock PaymentRefundPayuTransactionsRepository payuTransactionsRepository;
  @Mock AppServerOrderClient appServerOrderClient;
  @Mock PaytmVerificationClient paytmVerificationClient;
  @Mock PayuVerificationClient payuVerificationClient;
  @Mock KarixSmsClient karixSmsClient;
  @Mock PaymentRefundWhatsappClient whatsappClient;
  @Mock ChecksumRowWriter checksumRowWriter;

  private PaymentRefundOrderCreationService service;

  @BeforeEach
  void setUp() {
    service =
        new PaymentRefundOrderCreationService(
            checksumRequestRepository,
            orderRepository,
            orderRefundedRepository,
            userRepository,
            userOtpRepository,
            paytmStatusRepository,
            payuTransactionsRepository,
            appServerOrderClient,
            paytmVerificationClient,
            payuVerificationClient,
            karixSmsClient,
            whatsappClient,
            checksumRowWriter);
  }

  private static ChecksumRequestEntity row(String payingFor, String paymentGateway) {
    ChecksumRequestEntity row = new ChecksumRequestEntity();
    row.setId(CHECKSUM_ID);
    row.setUserId(USER_ID);
    row.setOrderId(ORDER_ID);
    row.setPayingFor(payingFor);
    row.setPaymentGateway(paymentGateway);
    row.setOrderRequestText("{\"category_id\":1}");
    return row;
  }

  private static User customer() {
    User customer = new User();
    customer.setMobile(9876543210L);
    return customer;
  }

  // --- already-handled short-circuits ---

  @Test
  void processRow_rowNoLongerExists_isAlreadyHandled() {
    when(checksumRequestRepository.findById(CHECKSUM_ID)).thenReturn(Optional.empty());

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.ALREADY_HANDLED);
  }

  @Test
  void processRow_unsupportedPayingFor_isSkippedNotFakedAsSuccess() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("PromoRecharge", "payu")));

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.UNSUPPORTED_PAYING_FOR);
    verify(orderRepository, never()).existsByPaymentSourceIdAndPaymentSourceIn(any(), any());
  }

  @Test
  void processRow_orderAlreadyExists_isNoOp() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "paytm")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(true);

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.ALREADY_HANDLED);
    verify(paytmVerificationClient, never()).validateTransaction(anyString());
  }

  @Test
  void processRow_refundAlreadyExists_isNoOp() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "paytm")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(true);

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.ALREADY_HANDLED);
    verify(paytmVerificationClient, never()).validateTransaction(anyString());
  }

  // --- Paytm branch ---

  @Test
  void processRow_paytmNotYetSuccessful_leavesRowPendingForNextTick() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "paytm")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(false);
    PaytmStatusResponse pending = new PaytmStatusResponse();
    pending.setSTATUS("PENDING");
    when(paytmVerificationClient.validateTransaction(PAYMENT_SOURCE_ID)).thenReturn(pending);

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.NOT_SUCCESSFUL_YET);
    verify(orderRefundedRepository, never()).save(any());
    verify(checksumRowWriter, never()).markHandled(any(), any());
  }

  @Test
  void processRow_paytmTxnSuccess_refundsAndMarksHandled() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "paytm")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(false);
    PaytmStatusResponse success = new PaytmStatusResponse();
    success.setSTATUS("TXN_SUCCESS");
    success.setTXNID("txn-1");
    success.setTXNAMOUNT("500");
    when(paytmVerificationClient.validateTransaction(PAYMENT_SOURCE_ID)).thenReturn(success);
    when(paytmVerificationClient.refund(PAYMENT_SOURCE_ID, "txn-1", "500"))
        .thenReturn(new PaytmRefundResult(true, "Refunded", "500"));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer()));

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.REFUND_ISSUED);
    // Two distinct rows: the validate-call snapshot (caller) and the refund-result snapshot
    // (attemptPaytmRefund) — this is the audit row that was missing before.
    verify(paytmStatusRepository, times(2)).save(any());
    verify(orderRefundedRepository).save(any());
    verify(checksumRowWriter).markHandled(CHECKSUM_ID, "Refund From Scheduler");
    verify(karixSmsClient).sendRefundNotice(9876543210L);
    verify(whatsappClient).sendRefundInitiated(anyString(), any(), eq("500"));
  }

  @Test
  void processRow_paytmRefundAttemptThrows_stillMarksHandledButReportsFailedNotIssued() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "paytm")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(false);
    PaytmStatusResponse success = new PaytmStatusResponse();
    success.setSTATUS("TXN_SUCCESS");
    success.setTXNID("txn-1");
    success.setTXNAMOUNT("500");
    when(paytmVerificationClient.validateTransaction(PAYMENT_SOURCE_ID)).thenReturn(success);
    when(paytmVerificationClient.refund(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("gateway timeout"));

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    // The DB write is source-faithful (unconditional), but the reported outcome must not lie.
    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.FAILED);
    verify(checksumRowWriter).markHandled(CHECKSUM_ID, "Refund From Scheduler");
    verify(orderRefundedRepository, never()).save(any());
  }

  // --- PayU branch ---

  @Test
  void processRow_payuSuccess_createsOrderWithoutRefunding() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "payu")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(false);
    when(payuVerificationClient.verifyPayment(PAYMENT_SOURCE_ID))
        .thenReturn(new PayuVerifyResult("success", "now", "mihpay-1", "500", "CC", "{}"));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer()));
    when(appServerOrderClient.verifyOtpAndGetBearerToken(any())).thenReturn("Bearer token");
    when(appServerOrderClient.createOrder(any(), eq("Bearer token"))).thenReturn(true);

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.ORDER_CREATED);
    verify(checksumRowWriter).markHandled(CHECKSUM_ID, "Order Created From Scheduler");
    verify(payuVerificationClient, never()).refund(anyString(), anyString());
    verify(orderRefundedRepository, never()).save(any());
  }

  @Test
  void processRow_payuNotYetSuccessful_leavesRowPendingForNextTick() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "payu")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(false);
    when(payuVerificationClient.verifyPayment(PAYMENT_SOURCE_ID))
        .thenReturn(new PayuVerifyResult("pending", "now", "mihpay-1", "500", "CC", "{}"));

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.NOT_SUCCESSFUL_YET);
    verify(appServerOrderClient, never()).verifyOtpAndGetBearerToken(any());
    verify(checksumRowWriter, never()).markHandled(any(), any());
  }

  @Test
  void payuFallbackRefund_decimalAmount_sentExactlyToGateway_notRoundedThroughFloat() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "payu")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(false);
    // "1299.50" would round to 1300 through Math.round(Float) — PayU would then reject it as
    // exceeding the refundable balance. The gateway call must receive "1299.50" verbatim.
    when(payuVerificationClient.verifyPayment(PAYMENT_SOURCE_ID))
        .thenReturn(new PayuVerifyResult("success", "now", "mihpay-1", "1299.50", "CC", "{}"));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer()));
    when(appServerOrderClient.verifyOtpAndGetBearerToken(any())).thenReturn("Bearer token");
    when(appServerOrderClient.createOrder(any(), eq("Bearer token"))).thenReturn(false);
    when(payuTransactionsRepository.findFirstByOrderIdOrderByIdDesc(PAYMENT_SOURCE_ID))
        .thenReturn(new PayuTransactionsEntity());
    when(payuVerificationClient.refund(eq("mihpay-1"), eq("1299.50")))
        .thenReturn(new PayuRefundResult(true, "Refunded", "req-1", 555L));

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.REFUND_ISSUED);
    verify(payuVerificationClient).refund("mihpay-1", "1299.50");

    ArgumentCaptor<Long> txnAmountCaptor = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<com.yesmadam.persistence.OrderRefunded> refundRowCaptor =
        ArgumentCaptor.forClass(com.yesmadam.persistence.OrderRefunded.class);
    verify(orderRefundedRepository).save(refundRowCaptor.capture());
    // The Long-typed audit column rounds HALF_UP (1299.50 -> 1300); the gateway amount above does
    // not — these are deliberately different values, checked separately.
    assertThat(refundRowCaptor.getValue().getTxnAmount()).isEqualTo(1300L);
  }

  @Test
  void payuFallbackRefund_unparsableAmount_neverCallsGateway_reportsFailedNotIssued() {
    when(checksumRequestRepository.findById(CHECKSUM_ID))
        .thenReturn(Optional.of(row("Service", "payu")));
    when(orderRepository.existsByPaymentSourceIdAndPaymentSourceIn(eq(PAYMENT_SOURCE_ID), any()))
        .thenReturn(false);
    when(orderRefundedRepository.existsByTransferId(PAYMENT_SOURCE_ID)).thenReturn(false);
    when(payuVerificationClient.verifyPayment(PAYMENT_SOURCE_ID))
        .thenReturn(new PayuVerifyResult("success", "now", "mihpay-1", "", "CC", "{}"));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer()));
    when(appServerOrderClient.verifyOtpAndGetBearerToken(any())).thenReturn("Bearer token");
    when(appServerOrderClient.createOrder(any(), eq("Bearer token"))).thenReturn(false);
    when(payuTransactionsRepository.findFirstByOrderIdOrderByIdDesc(PAYMENT_SOURCE_ID))
        .thenReturn(new PayuTransactionsEntity());

    PaymentRefundRowResult result = service.processRow(CHECKSUM_ID);

    assertThat(result.getOutcome()).isEqualTo(PaymentRefundRowOutcome.FAILED);
    verify(payuVerificationClient, never()).refund(anyString(), anyString());
    verify(orderRefundedRepository, never()).save(any());
    // Still marked handled — matches the source's unconditional behavior once the flow is reached.
    verify(checksumRowWriter).markHandled(CHECKSUM_ID, "Refund From Scheduler");
    verify(whatsappClient).sendRefundPending(anyString(), any());
  }

  // --- rule 9: bounded payload ---

  @Test
  void findPendingChecksumIds_returnsOnlyIds_notEntities() {
    ChecksumRequestEntity a = row("Service", "payu");
    a.setId(1L);
    ChecksumRequestEntity b = row("Service", "paytm");
    b.setId(2L);
    when(checksumRequestRepository.findAllByOrderCreatedAndSuccessAtBetweenAndPayingFor(
            eq(false), any(), any(), eq("Service")))
        .thenReturn(List.of(a, b));

    List<Long> ids =
        service.findPendingChecksumIds(new java.sql.Timestamp(0), new java.sql.Timestamp(1));

    assertThat(ids).containsExactly(1L, 2L);
  }
}
