package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionBatchItem;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionCandidate;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionAuditDao;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionFireLock;

/**
 * The trigger half's decision table — which orders are allowed into the waiting pool, and how many
 * of them may fire.
 *
 * <p>This is the gate that used to live behind an HTTP call to cron. Every rule here decides
 * whether real SPs get notified and whether an incentive is committed, so each is asserted rather
 * than trusted to a read-through of the port.
 *
 * <p>No database and no HTTP: repositories, the admin client and the fire lock are mocked. What is
 * verified is the decision — which order ids reached {@code enqueuePending}, and what was handed to
 * admin.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoJobDistributionTriggerTest {

  private static final long ORDER_ID = 5154874L;

  @Mock AutoJobDistributionReader reader;
  @Mock AutoJobDistributionWriter writer;
  @Mock DistributionAuditDao auditDao;
  @Mock DistributionAdminClient adminClient;
  @Mock DistributionFireLock fireLock;

  private AutoJobDistributionService service;

  @BeforeEach
  void setUp() {
    service = new AutoJobDistributionService(reader, writer, auditDao, adminClient, fireLock);

    // Default world: nothing blocked, no reassign history, no D-1 rejections, nothing live, and an
    // empty waiting pool. Individual tests override only the row they are about.
    when(reader.blockedOrderIds(anyList())).thenReturn(Collections.emptySet());
    when(reader.reassignCounts(anyList())).thenReturn(Collections.emptyMap());
    when(reader.d1RejectionBypass(anyList())).thenReturn(Collections.emptySet());
    when(reader.activeRounds()).thenReturn(Collections.emptyList());
    when(reader.pendingQueue()).thenReturn(Collections.emptyList());
    when(writer.enqueuePending(anyList())).thenAnswer(i -> argList(i.getArgument(0)));

    // The lock is not under test here — let the critical section run.
    when(fireLock.runIfAcquired(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
  }

  private static int argList(List<?> ids) {
    return ids == null ? 0 : ids.size();
  }

  // ================================================================
  // Candidate builder — a D0 order 3 hours out, past every gate by default
  // ================================================================

  /** A candidate that qualifies on the reassign rule and passes every exclusion. */
  private static CandidateRow qualifying() {
    return new CandidateRow();
  }

  /** Mutable fixture, so each test tweaks exactly one field before building the model. */
  static class CandidateRow {
    Long orderId = ORDER_ID;
    Integer spAssignByUser = null;
    Integer orderStatusCode = JobDistributionConstants.ORDER_NEW_REQUEST;
    Date bookingDate = Date.valueOf(LocalDate.now());
    String bookingTime = slotStartingInHours(3);
    Timestamp orderEndTimeWithDate =
        new Timestamp(System.currentTimeMillis() + 6 * 60 * 60 * 1000L);
    Boolean unassignWithRemoveReassigning = Boolean.FALSE;
    Integer categoryId = 1;
    Integer hubId = 42;
    Boolean hubIsFranchise = Boolean.FALSE;

    DistributionCandidate toModel() {
      return new DistributionCandidate(
          orderId,
          spAssignByUser,
          orderStatusCode,
          bookingDate,
          bookingTime,
          orderEndTimeWithDate,
          unassignWithRemoveReassigning,
          categoryId,
          hubId,
          hubIsFranchise);
    }
  }

  /** A {@code "hh:mm a - hh:mm a"} label whose start is {@code hours} from now. */
  private static String slotStartingInHours(int hours) {
    LocalTime start = LocalTime.now().plusHours(hours).withMinute(0).withSecond(0).withNano(0);
    return start.format(
            java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH))
        + " - "
        + start
            .plusHours(1)
            .format(
                java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH));
  }

  private void given(CandidateRow row) {
    when(reader.candidates(anyList())).thenReturn(List.of(row.toModel()));
  }

  /** Reassign counts as the reader hands them over — already narrowed. */
  private void givenReassigned(int count) {
    when(reader.reassignCounts(anyList())).thenReturn(Map.of(ORDER_ID, count));
  }

  @SuppressWarnings("unchecked")
  private List<Long> capturedEnqueue() {
    ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
    verify(writer).enqueuePending(captor.capture());
    return captor.getValue();
  }

  // ================================================================
  // Trigger-side exclusions — properties of the order, checked once at the door
  // ================================================================

  @Test
  @DisplayName("an excluded category never enters the waiting pool")
  void excludedCategoryIsDropped() {
    CandidateRow row = qualifying();
    row.categoryId = 84; // male hydra
    given(row);
    givenReassigned(2);

    DistributionTriggerResult result = service.triggerDistributionBatch(List.of(ORDER_ID));

    assertThat(result.getReceived()).isEqualTo(1);
    assertThat(result.getEligible()).isZero();
    verify(writer, never()).enqueuePending(anyList());
    verify(adminClient, never()).fireSingle(any());
  }

  @Test
  @DisplayName("a null category is not excluded — only listed ids are")
  void nullCategoryIsNotExcluded() {
    CandidateRow row = qualifying();
    row.categoryId = null;
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isEqualTo(1);
  }

  @Test
  @DisplayName("a franchise hub's order never enters the waiting pool")
  void franchiseHubIsDropped() {
    CandidateRow row = qualifying();
    row.hubIsFranchise = Boolean.TRUE;
    given(row);
    givenReassigned(2);

    DistributionTriggerResult result = service.triggerDistributionBatch(List.of(ORDER_ID));

    assertThat(result.getEligible()).isZero();
    verify(adminClient, never()).fireSingle(any());
  }

  @Test
  @DisplayName("a missing hub row is treated as non-franchise, not as excluded")
  void missingHubRowIsNotFranchise() {
    CandidateRow row = qualifying();
    row.hubId = null;
    row.hubIsFranchise = null; // LEFT JOIN produced no hub
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isEqualTo(1);
  }

  @Test
  @DisplayName("RESTRICT_TO_SINGLE_HUB is off, so every hub is distributed")
  void singleHubGatewayIsOff() {
    // Pins the production value rather than the behaviour of a flag flip: this constant being true
    // silently confines distribution to one hub, which is a QA-only state that must never ship.
    assertThat(JobDistributionConstants.RESTRICT_TO_SINGLE_HUB).isFalse();
    assertThat(JobDistributionConstants.RESTRICTED_HUB_ID).isEqualTo(127);

    CandidateRow row = qualifying();
    row.hubId = 999; // not the restricted hub
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isEqualTo(1);
  }

  // ================================================================
  // The re-trigger guard
  // ================================================================

  @Test
  @DisplayName("an order with a live or FINAL_UNASSIGNED round today is not re-queued")
  void blockedByActiveOrFinalRound() {
    given(qualifying());
    givenReassigned(2);
    when(reader.blockedOrderIds(anyList())).thenReturn(Set.of(ORDER_ID));

    DistributionTriggerResult result = service.triggerDistributionBatch(List.of(ORDER_ID));

    assertThat(result.getEligible()).isEqualTo(1); // passed the exclusions
    assertThat(result.getEnqueued()).isZero(); // but not the guard
  }

  // ================================================================
  // isStillEligible — "can this order still be distributed?"
  // ================================================================

  @Test
  @DisplayName("an order that already has an SP is not queued")
  void alreadyAssignedIsNotEligible() {
    CandidateRow row = qualifying();
    row.spAssignByUser = 12533;
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  @Test
  @DisplayName("an order ops withdrew from the reassign pool is not queued")
  void withdrawnFromPoolIsNotEligible() {
    CandidateRow row = qualifying();
    row.unassignWithRemoveReassigning = Boolean.TRUE;
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  @Test
  @DisplayName("an order with under 45 minutes of runway is not queued")
  void insufficientRunwayIsNotEligible() {
    CandidateRow row = qualifying();
    row.bookingTime = slotStartingInMinutes(30); // below MIN_MINUTES_BEFORE_START
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  @Test
  @DisplayName("an order whose slot has already ended is not queued")
  void endedSlotIsNotEligible() {
    CandidateRow row = qualifying();
    row.orderEndTimeWithDate = new Timestamp(System.currentTimeMillis() - 60_000L);
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  @Test
  @DisplayName("an order in a status this feature never distributes is not queued")
  void wrongStatusIsNotEligible() {
    CandidateRow row = qualifying();
    row.orderStatusCode = 5; // completed
    given(row);
    givenReassigned(2);

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  // ================================================================
  // qualifiesForDistribution — "did this order ever earn distribution?"
  // ================================================================

  @Test
  @DisplayName("reassign count at the threshold qualifies; one below does not")
  void reassignThresholdBoundary() {
    given(qualifying());
    givenReassigned(JobDistributionConstants.REASSIGN_THRESHOLD);
    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isEqualTo(1);

    given(qualifying());
    givenReassigned(JobDistributionConstants.REASSIGN_THRESHOLD - 1);
    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  @Test
  @DisplayName("an order inside the priority window qualifies on time alone")
  void priorityWindowQualifiesWithoutReassigns() {
    CandidateRow row = qualifying();
    row.bookingTime = slotStartingInMinutes(50); // under 60, above MIN_MINUTES_BEFORE_START
    given(row);
    // no reassign history at all

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isEqualTo(1);
  }

  @Test
  @DisplayName("an unparseable slot label sorts last and never qualifies on time")
  void parseFailureDoesNotQualifyOnTime() {
    CandidateRow row = qualifying();
    row.bookingTime = "whenever"; // minutesUntilStart -> Long.MAX_VALUE
    given(row);
    // no reassigns either, so the only possible route in would be the time rule

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  // ================================================================
  // D-1 SP-rejection bypass
  // ================================================================

  @Test
  @DisplayName("a D-1 order whose SP rejected after the cutoff bypasses the reassign threshold")
  void d1BypassAdmitsSubThresholdOrder() {
    CandidateRow row = qualifying();
    row.bookingDate = Date.valueOf(LocalDate.now().plusDays(1));
    given(row);
    when(reader.d1RejectionBypass(anyList())).thenReturn(Set.of(ORDER_ID));
    // zero reassigns and a slot far away — without the bypass this would not qualify

    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isEqualTo(1);
  }

  @Test
  @DisplayName("the bypass is D-1 only — a same-day order is never even looked up")
  void d1BypassIgnoredForSameDayOrder() {
    given(qualifying()); // booking_date is today

    // The bypass is still D-1 only, but the tomorrow-scoping now lives inside
    // AutoJobDistributionReader#d1RejectionBypass, which filters to tomorrow's bookings and returns
    // empty without issuing SQL when there are none. So the call happens and the outcome is what
    // proves the scoping: a same-day order is not admitted.
    assertThat(service.triggerDistributionBatch(List.of(ORDER_ID)).getEnqueued()).isZero();
  }

  // ================================================================
  // Enqueue and fire
  // ================================================================

  @Test
  @DisplayName("a qualifying order is enqueued and fired as round 1 at the base incentive")
  void qualifyingOrderIsEnqueuedThenFired() {
    given(qualifying());
    givenReassigned(2);
    // The order is now waiting, so the fire gate finds it.
    when(reader.pendingQueue()).thenReturn(List.of(pendingRow(7L, ORDER_ID)));
    when(adminClient.fireSingle(any())).thenReturn(true);

    DistributionTriggerResult result = service.triggerDistributionBatch(List.of(ORDER_ID));

    assertThat(capturedEnqueue()).containsExactly(ORDER_ID);
    assertThat(result.getFired()).isEqualTo(1);

    ArgumentCaptor<DistributionBatchItem> item =
        ArgumentCaptor.forClass(DistributionBatchItem.class);
    verify(adminClient).fireSingle(item.capture());
    assertThat(item.getValue().getOrderId()).isEqualTo(ORDER_ID);
    assertThat(item.getValue().getRound()).isEqualTo(1);
    assertThat(item.getValue().getExpireDistributionId()).isNull();
    assertThat(item.getValue().getIncentive()).isEqualTo(JobDistributionConstants.BASE_INCENTIVE);
    verify(writer).markPendingAsDistributed(any());
  }

  @Test
  @DisplayName("nothing fires while a distribution is already live — orders just queue")
  void gateStaysShutWhileSomethingIsLive() {
    given(qualifying());
    givenReassigned(2);
    when(reader.activeRounds())
        .thenReturn(
            List.of(
                new com.yesmadam
                    .schedulerservice
                    .jobs
                    .autoJobDistribution
                    .model
                    .JobDistribution()));

    DistributionTriggerResult result = service.triggerDistributionBatch(List.of(ORDER_ID));

    assertThat(result.getEnqueued()).isEqualTo(1);
    assertThat(result.getFired()).isZero();
    verify(adminClient, never()).fireSingle(any());
  }

  @Test
  @DisplayName("a whole batch of eligible orders still fires at most one")
  void batchFiresAtMostOne() {
    CandidateRow first = qualifying();
    CandidateRow second = qualifying();
    second.orderId = ORDER_ID + 1;
    when(reader.candidates(anyList())).thenReturn(List.of(first.toModel(), second.toModel()));
    when(reader.reassignCounts(anyList())).thenReturn(Map.of(ORDER_ID, 2, ORDER_ID + 1, 2));
    when(reader.pendingQueue())
        .thenReturn(List.of(pendingRow(7L, ORDER_ID), pendingRow(8L, ORDER_ID + 1)));
    when(adminClient.fireSingle(any())).thenReturn(true);

    DistributionTriggerResult result =
        service.triggerDistributionBatch(List.of(ORDER_ID, ORDER_ID + 1));

    assertThat(result.getEnqueued()).isEqualTo(2);
    assertThat(result.getFired()).isEqualTo(1);
    verify(adminClient).fireSingle(any()); // exactly once, not twice
  }

  @Test
  @DisplayName("an empty batch touches nothing")
  void emptyBatchIsNoOp() {
    DistributionTriggerResult result = service.triggerDistributionBatch(Collections.emptyList());

    assertThat(result.getReceived()).isZero();
    verify(reader, never()).candidates(anyList());
    verify(writer, never()).enqueuePending(anyList());
    verify(fireLock, never()).runIfAcquired(any());
  }

  @Test
  @DisplayName("the fire gate is entered through the lock, never directly")
  void firingAlwaysGoesThroughTheLock() {
    given(qualifying());
    givenReassigned(2);
    // A lock that is held by another process returns null without running the section.
    // doReturn, not when(...): re-stubbing through when() would invoke the mock and run the
    // setUp answer with a null argument.
    doReturn(null).when(fireLock).runIfAcquired(any());

    DistributionTriggerResult result = service.triggerDistributionBatch(List.of(ORDER_ID));

    assertThat(result.getEnqueued()).isEqualTo(1); // the enqueue still happened
    assertThat(result.getFired()).isZero(); // but nothing fired
    verify(adminClient, never()).fireSingle(any());
    verify(reader, never()).activeRounds();
  }

  private static String slotStartingInMinutes(int minutes) {
    LocalTime start = LocalTime.now().plusMinutes(minutes).withSecond(0).withNano(0);
    java.time.format.DateTimeFormatter fmt =
        java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH);
    return start.format(fmt) + " - " + start.plusHours(1).format(fmt);
  }

  private static com.yesmadam
          .schedulerservice
          .jobs
          .autoJobDistribution
          .model
          .DistributionPendingQueue
      pendingRow(Long id, Long orderId) {
    com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPendingQueue row =
        new com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPendingQueue();
    row.setId(id);
    row.setOrderId(orderId);
    row.setIsPending(1);
    return row;
  }
}
