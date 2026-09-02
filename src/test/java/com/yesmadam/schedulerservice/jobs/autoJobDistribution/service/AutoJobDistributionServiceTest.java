package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionBatchItem;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistribution;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionStatus;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.OrderAcceptanceView;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionAuditDao;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionFireLock;

/**
 * Covers the advance loop's decision table — which branch a live round takes, and what the
 * incentive escalates to. These are the rules that decide whether SPs get notified and how much
 * they are offered, so they are asserted rather than trusted to a read-through of the port.
 *
 * <p>No database and no HTTP: repositories and the admin client are mocked, and what is verified is
 * the decision (which status was written, what was handed to admin).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoJobDistributionServiceTest {

  private static final long ORDER_ID = 5154904L;
  private static final long ROUND_ID = 77L;

  @Mock AutoJobDistributionReader reader;
  @Mock AutoJobDistributionWriter writer;
  @Mock DistributionAuditDao auditDao;
  @Mock DistributionAdminClient adminClient;
  @Mock DistributionFireLock fireLock;

  private AutoJobDistributionService service() {
    // The lock is exercised in DistributionFireLockTest; here it always grants, so the loop's own
    // decisions are what is under test.
    when(fireLock.runIfAcquired(any())).thenReturn(Boolean.FALSE);
    return new AutoJobDistributionService(reader, writer, auditDao, adminClient, fireLock);
  }

  private JobDistribution liveRound(
      int roundNumber, int incentive, Integer notified, long ageMillis) {
    JobDistribution round = new JobDistribution();
    round.setId(ROUND_ID);
    round.setOrderId(ORDER_ID);
    round.setRoundNumber(roundNumber);
    round.setIncentiveAmount(incentive);
    round.setSpNotifiedCount(notified);
    round.setStatus(JobDistributionStatus.DISTRIBUTED.name());
    round.setIsActive(1);
    round.setScheduledAt(new Timestamp(System.currentTimeMillis() - ageMillis));
    return round;
  }

  /** Window elapsed — 6 minutes old against a 5-minute window. */
  private JobDistribution elapsedRound(int roundNumber, int incentive, Integer notified) {
    return liveRound(roundNumber, incentive, notified, 6 * 60_000L);
  }

  private static OrderAcceptanceView acceptance(
      Boolean accepted,
      Integer spId,
      Integer incentive,
      Integer statusCode,
      Timestamp endTime,
      java.sql.Date bookingDate) {
    return new OrderAcceptanceView(accepted, spId, incentive, statusCode, endTime, bookingDate);
  }

  private static java.sql.Date today() {
    return java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
  }

  private static Timestamp futureSlot() {
    return new Timestamp(System.currentTimeMillis() + 3 * 60 * 60_000L);
  }

  private void givenRound(JobDistribution round, OrderAcceptanceView view) {
    when(reader.activeRounds()).thenReturn(List.of(round));
    when(reader.acceptanceView(anyList())).thenReturn(Map.of(ORDER_ID, view));
  }

  private List<DistributionBatchItem> capturedAdvances() {
    ArgumentCaptor<List<DistributionBatchItem>> captor = ArgumentCaptor.forClass(List.class);
    verify(adminClient).fireBatch(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("an accepted round closes as ACCEPTED and records the SP and amount")
  void acceptedRoundClosesAsAccepted() {
    givenRound(elapsedRound(1, 100, 5), acceptance(true, 12533, 100, 3, futureSlot(), today()));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer).close(any(), eq(JobDistributionStatus.ACCEPTED), eq(12533), eq(100));
    assertThat(capturedAdvances()).isEmpty();
  }

  @Test
  @DisplayName("a cancelled order closes the round and never advances it")
  void cancelledOrderClosesRound() {
    // 17 = Order_CANCELLED_WB_DUPLICATE_TESTING — the code cron's own enum originally missed, which
    // let a cancelled order advance to round 2 on UAT order 5154874.
    givenRound(elapsedRound(1, 100, 5), acceptance(false, null, null, 17, futureSlot(), today()));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer).close(any(), eq(JobDistributionStatus.CANCELLED), any(), any());
    assertThat(capturedAdvances()).isEmpty();
  }

  @Test
  @DisplayName("an order assigned outside the flow closes as SUPERSEDED")
  void externallyAssignedOrderIsSuperseded() {
    givenRound(elapsedRound(1, 100, 5), acceptance(false, 999, null, 3, futureSlot(), today()));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer).close(any(), eq(JobDistributionStatus.SUPERSEDED), any(), any());
    assertThat(capturedAdvances()).isEmpty();
  }

  @Test
  @DisplayName("an order whose slot already ended closes as EXPIRED rather than escalating")
  void staleOrderExpiresInsteadOfEscalating() {
    // This is the order-5154904 scenario: a round sat live for ~17 hours, and on evaluation the old
    // code escalated it through every remaining round for a job whose slot had long passed.
    givenRound(
        elapsedRound(1, 100, 5),
        acceptance(
            false, null, null, 3, new Timestamp(System.currentTimeMillis() - 60_000L), today()));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer).close(any(), eq(JobDistributionStatus.EXPIRED), any(), any());
    assertThat(capturedAdvances()).isEmpty();
  }

  @Test
  @DisplayName("a round still inside its window is left alone")
  void roundInsideWindowIsLeftLive() {
    givenRound(
        liveRound(1, 100, 5, 60_000L), // 1 minute old, window is 5
        acceptance(false, null, null, 3, futureSlot(), today()));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer, never()).close(any(), any(), any(), any());
    assertThat(capturedAdvances()).isEmpty();
  }

  @Test
  @DisplayName("a round that reached SPs escalates by one step")
  void notifiedRoundEscalatesIncentive() {
    givenRound(elapsedRound(1, 100, 4), acceptance(false, null, null, 3, futureSlot(), today()));

    service().processActiveDistributions(ProgressReporter.noOp());

    assertThat(capturedAdvances())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getOrderId()).isEqualTo(ORDER_ID);
              assertThat(item.getExpireDistributionId()).isEqualTo(ROUND_ID);
              assertThat(item.getRound()).isEqualTo(2);
              assertThat(item.getIncentive()).isEqualTo(200);
            });
  }

  @Test
  @DisplayName("a dry round retries at the SAME incentive, not an escalated one")
  void dryRoundRetriesAtSameIncentive() {
    givenRound(elapsedRound(2, 200, 0), acceptance(false, null, null, 3, futureSlot(), today()));
    when(reader.notifiedCountsDesc(ORDER_ID)).thenReturn(List.of(0, 4));

    service().processActiveDistributions(ProgressReporter.noOp());

    // Escalating here would pay more for a round that never reached anybody.
    assertThat(capturedAdvances())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getRound()).isEqualTo(3);
              assertThat(item.getIncentive()).isEqualTo(200);
            });
  }

  @Test
  @DisplayName("three consecutive dry rounds give up as FINAL_UNASSIGNED")
  void threeConsecutiveDryRoundsGiveUp() {
    givenRound(elapsedRound(3, 100, 0), acceptance(false, null, null, 3, futureSlot(), today()));
    when(reader.notifiedCountsDesc(ORDER_ID)).thenReturn(List.of(0, 0, 0));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer).close(any(), eq(JobDistributionStatus.FINAL_UNASSIGNED), any(), any());
    assertThat(capturedAdvances()).isEmpty();
  }

  @Test
  @DisplayName("a notified round at the max incentive has nowhere to go and gives up")
  void maxIncentiveWithNotifiedRoundGivesUp() {
    givenRound(
        elapsedRound(3, JobDistributionConstants.MAX_INCENTIVE, 6),
        acceptance(false, null, null, 3, futureSlot(), today()));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer).close(any(), eq(JobDistributionStatus.FINAL_UNASSIGNED), any(), any());
    assertThat(capturedAdvances()).isEmpty();
  }

  @Test
  @DisplayName("a dry round still retries past the max incentive, since only dry streak caps it")
  void dryRoundAtMaxIncentiveStillRetries() {
    givenRound(
        elapsedRound(3, JobDistributionConstants.MAX_INCENTIVE, 0),
        acceptance(false, null, null, 3, futureSlot(), today()));
    when(reader.notifiedCountsDesc(ORDER_ID)).thenReturn(List.of(0, 5));

    service().processActiveDistributions(ProgressReporter.noOp());

    // MAX_INCENTIVE only ends things for a round that actually reached SPs — a dry round keeps
    // retrying at the same amount until the consecutive-dry cap.
    assertThat(capturedAdvances())
        .singleElement()
        .satisfies(
            item ->
                assertThat(item.getIncentive()).isEqualTo(JobDistributionConstants.MAX_INCENTIVE));
  }

  @Test
  @DisplayName("a rescheduled order closes as RESCHEDULED")
  void rescheduledOrderClosesAsRescheduled() {
    java.sql.Date nextWeek =
        java.sql.Date.valueOf(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(7));
    givenRound(elapsedRound(1, 100, 5), acceptance(false, null, null, 3, futureSlot(), nextWeek));

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(writer).close(any(), eq(JobDistributionStatus.RESCHEDULED), any(), any());
  }

  @Test
  @DisplayName("no live rounds still gives the waiting pool a chance")
  void noActiveRoundsStillChecksPendingPool() {
    when(reader.activeRounds()).thenReturn(Collections.emptyList());

    service().processActiveDistributions(ProgressReporter.noOp());

    verify(fireLock).runIfAcquired(any());
    verify(adminClient, never()).fireBatch(anyList());
  }

  @Test
  @DisplayName("consecutive dry rounds are counted only up to the first round that reached SPs")
  void countsOnlyTheMostRecentDryStreak() {
    assertThat(AutoJobDistributionService.countConsecutiveZeroRounds(List.of(0, 0, 3, 0, 0)))
        .isEqualTo(2);
    assertThat(AutoJobDistributionService.countConsecutiveZeroRounds(List.of(4, 0, 0))).isZero();
    assertThat(AutoJobDistributionService.countConsecutiveZeroRounds(Collections.emptyList()))
        .isZero();
  }
}
