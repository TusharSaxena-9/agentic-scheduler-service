package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryCandidateMessage;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryReminderChunkResult;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.model.WalletExpiryRunWindow;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.projections.WalletExpiryCandidateProjection;

/** Service-layer tests for the wallet-expiry enqueue job (CLAUDE.md rule 12). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletExpiryReminderServiceImplTest {

  private static final String QUEUE_URL =
      "https://sqs.ap-south-1.amazonaws.com/098102135019/wallet-expiry-notification-uat";
  private static final Timestamp EXPIRE_AT = Timestamp.valueOf("2026-10-08 00:00:00");

  @Mock WalletExpiryReminderReader reader;
  @Mock WalletExpirySqsProducer sqsProducer;

  private WalletExpiryReminderServiceImpl service;
  private WalletExpiryRunWindow window;

  @BeforeEach
  void setUp() {
    service = new WalletExpiryReminderServiceImpl(reader, sqsProducer);
    LocalDate walletExpireDate = LocalDate.of(2026, 10, 8);
    window = new WalletExpiryRunWindow(
        30,
        Timestamp.valueOf(walletExpireDate.atStartOfDay()),
        Timestamp.valueOf(walletExpireDate.plusDays(1).atStartOfDay()),
        walletExpireDate);
  }

  private static WalletExpiryCandidateProjection candidate(long userId) {
    return candidate(userId, 9000000000L + userId, 250, 500);
  }

  private static WalletExpiryCandidateProjection candidate(
      long userId, long mobile, Integer walletMin, Integer amountLimit) {
    return new WalletExpiryCandidateProjection() {
      @Override
      public Long getUserId() {
        return userId;
      }

      @Override
      public Long getMobile() {
        return mobile;
      }

      @Override
      public Integer getWalletMin() {
        return walletMin;
      }

      @Override
      public Integer getAmountLimit() {
        return amountLimit;
      }

      @Override
      public Timestamp getExpireAt() {
        return EXPIRE_AT;
      }
    };
  }

  @Test
  void processWindow_shouldSkipPublishingWhenThereAreNoCandidates() {
    when(reader.fetchCandidates(any(), any())).thenReturn(List.of());

    WalletExpiryReminderChunkResult result =
        service.processWindow(window, QUEUE_URL, ProgressReporter.noOp());

    assertThat(result.getCandidateCount()).isZero();
    verify(sqsProducer, never()).publishBatch(any(), any(), any());
  }

  @Test
  void processWindow_shouldFetchEveryCandidateInOneQueryAndPublishThemAllInOneCall() {
    when(reader.fetchCandidates(window.getDayStart(), window.getDayEnd()))
        .thenReturn(List.of(
            candidate(101L, 9876543101L, 250, 500),
            candidate(102L, 9876543102L, 300, 0),
            candidate(103L, 9876543103L, 400, null)));

    WalletExpiryReminderChunkResult result =
        service.processWindow(window, QUEUE_URL, ProgressReporter.noOp());

    assertThat(result.getCandidateCount()).isEqualTo(3);
    verify(sqsProducer).publishBatch(
        eq(QUEUE_URL),
        eq(List.of(
            new WalletExpiryCandidateMessage(101L, 30, 9876543101L, 250, 500, EXPIRE_AT.getTime()),
            new WalletExpiryCandidateMessage(102L, 30, 9876543102L, 300, 0, EXPIRE_AT.getTime()),
            new WalletExpiryCandidateMessage(103L, 30, 9876543103L, 400, null, EXPIRE_AT.getTime()))),
        any());
  }

  @Test
  void processWindow_shouldCarryQueryTimingAndRowCount() {
    when(reader.fetchCandidates(any(), any())).thenReturn(List.of(candidate(101L)));

    WalletExpiryReminderChunkResult result =
        service.processWindow(window, QUEUE_URL, ProgressReporter.noOp());

    assertThat(result.getQueryDurationsMs()).containsKey("fetchCandidates");
    assertThat(result.getQueryRowCounts()).containsEntry("fetchCandidates", 1);
  }

  @Test
  void processWindow_shouldPropagateAPublishFailureSoTemporalRetriesTheWindow() {
    when(reader.fetchCandidates(any(), any())).thenReturn(List.of(candidate(101L)));

    doThrow(new IllegalStateException("SQS publish failed"))
        .when(sqsProducer)
        .publishBatch(eq(QUEUE_URL), any(), any());

    assertThatThrownBy(() -> service.processWindow(window, QUEUE_URL, ProgressReporter.noOp()))
        .isInstanceOf(IllegalStateException.class);
  }
}
