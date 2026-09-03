package com.yesmadam.schedulerservice.jobs.autoassign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.service.AutoJobDistributionService;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignOutcome;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignCandidate;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignThresholds;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;

/**
 * The auto-assign gate's decision table: for each candidate, assign via app-server, or hand it to
 * Auto Job Distribution.
 *
 * <p>The gate is the whole point of the port, and it is a three-term conjunction where every term
 * has a boundary worth pinning. Getting any of them backwards either floods distribution with
 * orders that should have been auto-assigned, or silently strands orders that needed escalating.
 *
 * <p>No database and no HTTP: the reader, the API client and the distribution engine are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoAssignServiceImplTest {

  private static final long ORDER_ID = 4242L;

  /**
   * Reassign threshold 2, priority window 60 min, min lead time 60 min, express start threshold 45
   * min, express age lookback 15 min — the shipped defaults.
   */
  private static final AutoAssignThresholds DEFAULTS = new AutoAssignThresholds(2, 60, 60, 45, 15);

  @Mock AutoAssignOrderReader reader;
  @Mock SettingService settingService;
  @Mock AutoAssignApiClient apiClient;
  @Mock AutoJobDistributionService distributionService;

  private AutoAssignServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AutoAssignServiceImpl(reader, settingService, apiClient, distributionService);

    // Default world: no reassign history, nothing accepted-then-cancelled, assign call succeeds.
    when(reader.findReassignCounts(anyList())).thenReturn(Collections.emptyMap());
    when(reader.findAcceptedDistributionOrderIds(anyList())).thenReturn(Collections.emptyList());
    when(reader.findSlotStartMillis(anyList())).thenReturn(Collections.emptyMap());
    when(reader.findSpRejectedAfterCutoff(anyList(), any())).thenReturn(Collections.emptyList());
    when(apiClient.triggerAssign(anyLong())).thenReturn(true);
  }

  /** A candidate whose slot starts {@code minutes} from now. */
  private static AutoAssignCandidate candidate(long minutes) {
    return new AutoAssignCandidate(ORDER_ID, System.currentTimeMillis() + minutes * 60_000L);
  }

  /** An express-flagged candidate whose slot starts {@code startMinutes} from now. */
  private static AutoAssignCandidate expressCandidate(long startMinutes, long createdMinutesAgo) {
    return new AutoAssignCandidate(
        ORDER_ID,
        System.currentTimeMillis() + startMinutes * 60_000L,
        true,
        System.currentTimeMillis() - createdMinutesAgo * 60_000L);
  }

  /** Same-day run — the default for the gate tests below. */
  private AutoAssignChunkResult process(AutoAssignCandidate candidate) {
    return service.processChunk(
        List.of(candidate), DEFAULTS, AutoAssignScope.SAME_DAY, ProgressReporter.noOp());
  }

  /** Next-day run — applies the extra SP-rejection term. */
  private AutoAssignChunkResult processNextDay(AutoAssignCandidate candidate) {
    return service.processChunk(
        List.of(candidate), DEFAULTS, AutoAssignScope.NEXT_DAY, ProgressReporter.noOp());
  }

  // ================================================================
  // The three gate terms
  // ================================================================

  @Test
  @DisplayName("a fresh order well clear of its slot is auto-assigned")
  void freshOrderIsAssigned() {
    AutoAssignChunkResult result = process(candidate(180));

    assertThat(result.count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
    assertThat(result.getDistributionOrderIds()).isEmpty();
    verify(apiClient).triggerAssign(ORDER_ID);
  }

  @Test
  @DisplayName("reassign count below the threshold still auto-assigns; at the threshold it diverts")
  void reassignThresholdBoundary() {
    when(reader.findReassignCounts(anyList())).thenReturn(Map.of(ORDER_ID, 1));
    assertThat(process(candidate(180)).count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);

    when(reader.findReassignCounts(anyList())).thenReturn(Map.of(ORDER_ID, 2));
    AutoAssignChunkResult diverted = process(candidate(180));
    assertThat(diverted.count(AutoAssignOutcome.QUEUED_FOR_DISTRIBUTION)).isEqualTo(1);
    assertThat(diverted.getDistributionOrderIds()).containsExactly(ORDER_ID);
  }

  @Test
  @DisplayName("an order an SP accepted then cancelled skips auto-assign entirely")
  void acceptedThenCancelledIsDiverted() {
    when(reader.findAcceptedDistributionOrderIds(anyList())).thenReturn(List.of(ORDER_ID));

    AutoAssignChunkResult result = process(candidate(180));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
    verify(apiClient, never()).triggerAssign(anyLong());
  }

  @Test
  @DisplayName("an order inside the priority window diverts regardless of reassign count")
  void insidePriorityWindowIsDiverted() {
    AutoAssignChunkResult result = process(candidate(30)); // under the 60-minute window

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
    verify(apiClient, never()).triggerAssign(anyLong());
  }

  @Test
  @DisplayName("exactly at the priority threshold the order is still auto-assigned")
  void atPriorityThresholdIsAssigned() {
    // The gate is `minutesUntilStart >= threshold`, so the boundary itself stays on the assign
    // path.
    // A minute of slack absorbs the clock ticking between constructing the candidate and reading
    // it.
    AutoAssignChunkResult result = process(candidate(61));

    assertThat(result.count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
  }

  // ================================================================
  // Parse failure — must never force-queue for distribution
  // ================================================================

  @Test
  @DisplayName("an unresolvable slot start keeps the order on the auto-assign path")
  void unresolvableSlotStartKeepsAutoAssign() {
    // Null start time means "start unknown", which the gate reads as infinitely far away — the
    // cron's parse-failure-is-safe behaviour. The alternative, treating unknown as urgent, would
    // dump every order with a malformed slot label straight into distribution.
    AutoAssignChunkResult result =
        service.processChunk(
            List.of(new AutoAssignCandidate(ORDER_ID, null)),
            DEFAULTS,
            AutoAssignScope.SAME_DAY,
            ProgressReporter.noOp());

    assertThat(result.count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
    assertThat(result.getDistributionOrderIds()).isEmpty();
  }

  @Test
  @DisplayName("a re-read that yields an unparseable slot also keeps the auto-assign path")
  void freshReadParseFailureKeepsAutoAssign() {
    // Present in the re-read but with a null value: the label is there and will not parse.
    when(reader.findSlotStartMillis(anyList()))
        .thenReturn(Collections.singletonMap(ORDER_ID, null));

    AutoAssignChunkResult result = process(candidate(30)); // context said "urgent"

    assertThat(result.count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
  }

  // ================================================================
  // P5 — the per-chunk re-read is what the gate actually uses
  // ================================================================

  @Test
  @DisplayName("the fresh slot re-read overrides the run context, not the other way round")
  void freshSlotReadWinsOverRunContext() {
    // Context recorded a slot 3 hours out; the order has since been rescheduled to 20 minutes out.
    // The gate must divert it, which it can only do if it reads the fresh value.
    when(reader.findSlotStartMillis(anyList()))
        .thenReturn(Map.of(ORDER_ID, System.currentTimeMillis() + 20 * 60_000L));

    AutoAssignChunkResult result = process(candidate(180));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
  }

  @Test
  @DisplayName("an order missing from the re-read falls back to the run context value")
  void missingFromFreshReadFallsBackToContext() {
    // Empty re-read (order gone mid-run). The context value is the more conservative choice.
    when(reader.findSlotStartMillis(anyList())).thenReturn(Collections.emptyMap());

    assertThat(process(candidate(180)).count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
  }

  // ================================================================
  // Assign failures are absorbed, not thrown
  // ================================================================

  @Test
  @DisplayName("a failed assign is counted, not raised — the chunk stays non-throwing")
  void failedAssignIsCountedNotThrown() {
    when(apiClient.triggerAssign(anyLong())).thenReturn(false);

    AutoAssignChunkResult result = process(candidate(180));

    assertThat(result.count(AutoAssignOutcome.ASSIGN_FAILED)).isEqualTo(1);
    assertThat(result.count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isZero();
    // Not added to the distribution list: cron retried on the next tick rather than escalating.
    assertThat(result.getDistributionOrderIds()).isEmpty();
  }

  @Test
  @DisplayName("an empty chunk short-circuits before any query")
  void emptyChunkIsNoOp() {
    AutoAssignChunkResult result =
        service.processChunk(
            Collections.emptyList(), DEFAULTS, AutoAssignScope.SAME_DAY, ProgressReporter.noOp());

    assertThat(result.getEvaluated()).isZero();
    verify(reader, never()).findReassignCounts(anyList());
    verify(reader, never()).findSlotStartMillis(anyList());
  }

  // ================================================================
  // The distribution handoff is in-process, not HTTP
  // ================================================================

  @Test
  @DisplayName("dispatchDistributionBatch calls the engine directly and returns its result")
  void dispatchGoesToTheEngine() {
    DistributionTriggerResult engineResult = new DistributionTriggerResult(2, 2, 1, 1, 1);
    when(distributionService.triggerDistributionBatch(anyList())).thenReturn(engineResult);

    DistributionTriggerResult result = service.dispatchDistributionBatch(List.of(1L, 2L));

    assertThat(result).isSameAs(engineResult);
    verify(distributionService).triggerDistributionBatch(List.of(1L, 2L));
  }

  @Test
  @DisplayName("an empty distribution batch never reaches the engine")
  void emptyDispatchIsNoOp() {
    DistributionTriggerResult result = service.dispatchDistributionBatch(Collections.emptyList());

    assertThat(result.getReceived()).isZero();
    verify(distributionService, never()).triggerDistributionBatch(any());
  }

  // ================================================================
  // D-1 scope: the one gate term that differs between the two source crons
  // ================================================================

  @Test
  @DisplayName("D-1: an order whose SP rejected it after the cutoff is diverted to distribution")
  void nextDaySpRejectionDivertsOrder() {
    // Zero reassigns and a slot far out, so the only thing that can divert it is the bypass term.
    when(reader.findSpRejectedAfterCutoff(anyList(), any())).thenReturn(List.of(ORDER_ID));

    AutoAssignChunkResult result = processNextDay(candidate(600));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
    verify(apiClient, never()).triggerAssign(anyLong());
  }

  @Test
  @DisplayName("D-1: without a rejection the order still auto-assigns normally")
  void nextDayWithoutRejectionStillAssigns() {
    AutoAssignChunkResult result = processNextDay(candidate(600));

    assertThat(result.count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
    assertThat(result.getDistributionOrderIds()).isEmpty();
  }

  @Test
  @DisplayName("same-day: the bypass query is never even issued")
  void sameDayNeverQueriesTheBypass() {
    // The D0 cron does not compute this set at all. Issuing it would be a behavioural change and a
    // wasted per-chunk round trip against the largest table in the schema.
    process(candidate(600));

    verify(reader, never()).findSpRejectedAfterCutoff(anyList(), any());
  }

  @Test
  @DisplayName("same-day: a rejection recorded for the order does not divert it")
  void sameDayIgnoresRejections() {
    // Even if the reader were somehow to return a hit, SAME_DAY must not act on it.
    when(reader.findSpRejectedAfterCutoff(anyList(), any())).thenReturn(List.of(ORDER_ID));

    assertThat(process(candidate(600)).count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
  }

  // ================================================================
  // Express last-resort bypass: SAME_DAY only, independent of the other three terms
  // ================================================================

  @Test
  @DisplayName("SAME_DAY: a fresh express order about to start bypasses every other check")
  void expressLastResortBypassesEveryOtherCheck() {
    // Reassign count over the threshold AND already accepted-then-cancelled: either alone would
    // normally divert the order. The express bypass must fire anyway — it is a last resort, not an
    // additional condition on top of the normal gate.
    when(reader.findReassignCounts(anyList())).thenReturn(Map.of(ORDER_ID, 99));
    when(reader.findAcceptedDistributionOrderIds(anyList())).thenReturn(List.of(ORDER_ID));

    AutoAssignChunkResult result = process(expressCandidate(10, 5));

    assertThat(result.count(AutoAssignOutcome.ASSIGN_TRIGGERED)).isEqualTo(1);
    assertThat(result.getDistributionOrderIds()).isEmpty();
    verify(apiClient).triggerAssign(ORDER_ID);
  }

  @Test
  @DisplayName("SAME_DAY: an express order created too long ago does not get the bypass")
  void expressButStaleIsDiverted() {
    AutoAssignChunkResult result = process(expressCandidate(10, 20));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
    verify(apiClient, never()).triggerAssign(anyLong());
  }

  @Test
  @DisplayName("SAME_DAY: an express order 45-60 minutes out gets neither gate term")
  void expressNotCloseEnoughIsDiverted() {
    // Inside the normal 60-minute priority window (so safeToReassign fails) but not yet inside the
    // 45-minute express window (so expressLastResort fails too).
    AutoAssignChunkResult result = process(expressCandidate(50, 5));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
  }

  @Test
  @DisplayName("SAME_DAY: at the 45-minute express threshold the bypass does not apply")
  void expressAtStartBoundaryIsDiverted() {
    // Mirrors atPriorityThresholdIsAssigned's slack: constructing at exactly 45 and reading it a
    // few ms later could truncate to 44 minutes, which would wrongly land inside the bypass. 46
    // safely stays at-or-above 45 after that drift, so the strict `<` comparison still reads false.
    AutoAssignChunkResult result = process(expressCandidate(46, 5));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
  }

  @Test
  @DisplayName("SAME_DAY: at the 15-minute express age lookback the bypass does not apply")
  void expressAtAgeBoundaryIsDiverted() {
    AutoAssignChunkResult result = process(expressCandidate(10, 16));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
  }

  @Test
  @DisplayName("SAME_DAY: a non-express order in the express window is still diverted")
  void nonExpressOrderGetsNoBypass() {
    // Same timing as expressLastResortBypassesEveryOtherCheck, but expressJob=false — a regression
    // guard that the new term does not leak into ordinary orders.
    AutoAssignChunkResult result = process(candidate(10));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
    verify(apiClient, never()).triggerAssign(anyLong());
  }

  @Test
  @DisplayName("SAME_DAY: an express order with unresolved creation time does not get the bypass")
  void expressWithUnknownCreatedAtFailsClosed() {
    // createdAtMillis null reads as "created infinitely long ago" -- fails the express check
    // closed, the opposite direction from how an unresolved slot start fails the normal check open.
    AutoAssignCandidate unresolvedCreatedAt =
        new AutoAssignCandidate(ORDER_ID, System.currentTimeMillis() + 10 * 60_000L, true, null);

    AutoAssignChunkResult result = process(unresolvedCreatedAt);

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
  }

  @Test
  @DisplayName("NEXT_DAY: the express bypass never applies, even to a fresh about-to-start order")
  void nextDayNeverAppliesExpressBypass() {
    AutoAssignChunkResult result = processNextDay(expressCandidate(10, 5));

    assertThat(result.getDistributionOrderIds()).containsExactly(ORDER_ID);
    verify(apiClient, never()).triggerAssign(anyLong());
  }

  // ================================================================
  // Scope drives the candidate query and the booking date
  // ================================================================

  @Test
  @DisplayName("the run context targets today for D0 and tomorrow for D-1")
  void runContextResolvesBookingDatePerScope() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(Collections.emptyMap());
    when(reader.findUnassignedOrders(any(), anyList(), any(), any()))
        .thenReturn(Collections.emptyList());

    assertThat(service.fetchRunContext(AutoAssignScope.SAME_DAY).getBookingDate())
        .isEqualTo(LocalDate.now().toString());
    assertThat(service.fetchRunContext(AutoAssignScope.NEXT_DAY).getBookingDate())
        .isEqualTo(LocalDate.now().plusDays(1).toString());
  }

  @Test
  @DisplayName("the scope is passed to the reader so it can pick the lead-time variant")
  void runContextPassesScopeToReader() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(Collections.emptyMap());
    when(reader.findUnassignedOrders(any(), anyList(), any(), any()))
        .thenReturn(Collections.emptyList());

    service.fetchRunContext(AutoAssignScope.NEXT_DAY);

    // NEXT_DAY carries no lead-time floor; the reader is what acts on that, so it must see the
    // scope.
    verify(reader).findUnassignedOrders(eq(AutoAssignScope.NEXT_DAY), anyList(), any(), any());
    assertThat(AutoAssignScope.NEXT_DAY.isLeadTimeFloorApplied()).isFalse();
    assertThat(AutoAssignScope.SAME_DAY.isLeadTimeFloorApplied()).isTrue();
  }

  @Test
  @DisplayName("the run context records its own scope for the workflow to pass to each chunk")
  void runContextCarriesScope() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(Collections.emptyMap());
    when(reader.findUnassignedOrders(any(), anyList(), any(), any()))
        .thenReturn(Collections.emptyList());

    assertThat(service.fetchRunContext(AutoAssignScope.NEXT_DAY).getScope())
        .isEqualTo(AutoAssignScope.NEXT_DAY);
  }

  // ================================================================
  // Progress reporting -- what the activity turns into a heartbeat
  // ================================================================

  @Test
  @DisplayName("progress is reported once per order, so a wedged chunk is visible mid-flight")
  void progressIsReportedPerOrder() {
    // The activity adapter maps each report to Activity.getExecutionContext().heartbeat(...). If
    // the
    // count were reported only once at the end of the chunk, a hung assign call would look
    // identical
    // to a healthy long chunk and the heartbeat timeout could never fire.
    List<Integer> reported = new ArrayList<>();
    List<AutoAssignCandidate> chunk =
        List.of(candidate(180), new AutoAssignCandidate(ORDER_ID + 1, null));

    service.processChunk(chunk, DEFAULTS, AutoAssignScope.SAME_DAY, reported::add);

    assertThat(reported).containsExactly(1, 2);
  }

  @Test
  @DisplayName("a throwing reporter aborts the chunk rather than being swallowed")
  void reporterFailurePropagates() {
    // Deliberately NOT swallowed. In production the reporter is Temporal's heartbeat, which throws
    // ActivityCompletionException once the activity is cancelled or already timed out -- and that
    // throw is how cancellation reaches a running activity. Catching it would leave this loop
    // firing
    // assign calls for a run Temporal has abandoned, which is the leak heartbeating exists to
    // close.
    assertThatThrownBy(
            () ->
                service.processChunk(
                    List.of(candidate(180)),
                    DEFAULTS,
                    AutoAssignScope.SAME_DAY,
                    completed -> {
                      throw new IllegalStateException("activity cancelled");
                    }))
        .isInstanceOf(IllegalStateException.class);
  }
}
