package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

/**
 * Service-layer tests for the auto SP-availability-on job (CLAUDE.md rule 12 — the service layer is
 * what gets tested, not the workflow). Plain JUnit and Mockito; nothing from {@code io.temporal} is
 * reachable from here, which is the point of keeping the logic out of the activity.
 */
@ExtendWith(MockitoExtension.class)
class SpAvailabilityAutoOnServiceImplTest {

  /** A Wednesday, so nothing about the expected window depends on a weekend boundary. */
  private static final LocalDate RUN_DATE = LocalDate.of(2026, 9, 16);

  /** The 23:30 flip's window: D-2 relative to {@link #RUN_DATE}, whole day, inclusive both ends. */
  private static final Timestamp EXPECTED_WINDOW_START = Timestamp.valueOf("2026-09-14 00:00:00.0");

  private static final Timestamp EXPECTED_WINDOW_END = Timestamp.valueOf("2026-09-14 23:59:59.0");

  /** The 20:00 notify's window: D-1, one day ahead of the flip's. */
  private static final Timestamp EXPECTED_NOTIFY_START = Timestamp.valueOf("2026-09-15 00:00:00.0");

  private static final Timestamp EXPECTED_NOTIFY_END = Timestamp.valueOf("2026-09-15 23:59:59.0");

  private static final int BATCH_SIZE = 3;

  @Mock SpAvailabilityAutoOnReader reader;
  @Mock SpAvailabilityAutoOnWriter writer;

  private SpAvailabilityAutoOnServiceImpl service() {
    return new SpAvailabilityAutoOnServiceImpl(reader, writer, BATCH_SIZE);
  }

  private SpAvailabilityAutoOnWindow window() {
    return new SpAvailabilityAutoOnWindow(
        EXPECTED_WINDOW_START, EXPECTED_WINDOW_END, Timestamp.valueOf("2026-09-16 00:30:00.0"));
  }

  @Test
  @DisplayName("the flip window is D-2, whole day, inclusive at both ends")
  void flipWindowIsTwoDaysBack() {
    SpAvailabilityAutoOnWindow window = service().resolveWindow(RUN_DATE);

    assertThat(window.getWindowStart()).isEqualTo(EXPECTED_WINDOW_START);
    assertThat(window.getWindowEnd()).isEqualTo(EXPECTED_WINDOW_END);
    assertThat(window.getActionAt()).isNotNull();
  }

  @Test
  @DisplayName("consecutive days tile at whole-second resolution — no gap, no overlap")
  void consecutiveWindowsTile() {
    Timestamp todaysEnd = service().resolveWindow(RUN_DATE).getWindowEnd();
    Timestamp tomorrowsStart = service().resolveWindow(RUN_DATE.plusDays(1)).getWindowStart();

    // Inclusive-inclusive bounds meet one second apart rather than at the same instant, which is
    // what stops an approval being counted by two consecutive runs. It leaves no gap only because
    // approvedat stores whole seconds — if that column is ever widened to timestamp(3)/(6), this
    // must become a half-open >= / < bound, and this assertion is where that shows up.
    assertThat(tomorrowsStart.getTime() - todaysEnd.getTime()).isEqualTo(1000L);
  }

  @Test
  @DisplayName("the notify window is D-1 — one day ahead of the flip's")
  void notifyWindowIsOneDayBack() {
    SpAvailabilityAutoOnWindow window = service().resolveWindowNotification(RUN_DATE);

    assertThat(window.getWindowStart()).isEqualTo(EXPECTED_NOTIFY_START);
    assertThat(window.getWindowEnd()).isEqualTo(EXPECTED_NOTIFY_END);
  }

  @Test
  @DisplayName("the two offsets pair up: tonight's notify cohort is tomorrow night's flip cohort")
  void notifyAndFlipCoverTheSameCohortADayApart() {
    // This is the whole schedule of the flow, and nothing else enforces it. An SP approved on
    // Monday is notified on Tuesday at 20:00 (D-1) and switched on Wednesday at 23:30 (D-2).
    // Move either offset without the other and the two jobs act on different cohorts, silently.
    SpAvailabilityAutoOnWindow notifyTonight = service().resolveWindowNotification(RUN_DATE);
    SpAvailabilityAutoOnWindow flipTomorrow = service().resolveWindow(RUN_DATE.plusDays(1));

    assertThat(notifyTonight.getWindowStart()).isEqualTo(flipTomorrow.getWindowStart());
    assertThat(notifyTonight.getWindowEnd()).isEqualTo(flipTomorrow.getWindowEnd());
  }

  @Test
  @DisplayName("each run covers exactly one calendar day")
  void windowSpansASingleDay() {
    SpAvailabilityAutoOnWindow window = service().resolveWindow(RUN_DATE);

    assertThat(window.getWindowStart().toLocalDateTime().toLocalDate())
        .isEqualTo(window.getWindowEnd().toLocalDateTime().toLocalDate())
        .isEqualTo(RUN_DATE.minusDays(2));
  }

  @Test
  @DisplayName("an empty page reports the window exhausted and never touches the writer")
  void emptyPageShortCircuits() {
    when(reader.findEligibleSpIds(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

    SpKitCohortBatch batch = service().flipNextBatch(window(), 0);

    assertThat(batch.getSpIds()).isEmpty();
    assertThat(batch.getCandidateCount()).isZero();
    assertThat(batch.isExhausted()).isTrue();
    assertThat(batch.getLastSpId()).isZero();
    verifyNoInteractions(writer);
  }

  @Test
  @DisplayName("the reader is queried with the window and cursor it was handed")
  void readerGetsTheWindowAndCursorVerbatim() {
    when(reader.findEligibleSpIds(EXPECTED_WINDOW_START, EXPECTED_WINDOW_END, 42, BATCH_SIZE))
        .thenReturn(List.of());

    assertThat(service().flipNextBatch(window(), 42).isExhausted()).isTrue();
  }

  @Test
  @DisplayName("a short page is flipped and marked exhausted")
  void shortPageIsTheLastPage() {
    when(reader.findEligibleSpIds(EXPECTED_WINDOW_START, EXPECTED_WINDOW_END, 0, BATCH_SIZE))
        .thenReturn(List.of(11, 22));
    when(writer.turnAvailabilityOn(eq(List.of(11, 22)), any())).thenReturn(List.of(11, 22));

    SpKitCohortBatch batch = service().flipNextBatch(window(), 0);

    assertThat(batch.getSpIds()).containsExactly(11, 22);
    assertThat(batch.getCandidateCount()).isEqualTo(2);
    assertThat(batch.getLastSpId()).isEqualTo(22);
    assertThat(batch.isExhausted()).isTrue();
  }

  @Test
  @DisplayName("a full page advances the cursor to its last id and is not exhausted")
  void fullPageAdvancesTheCursor() {
    when(reader.findEligibleSpIds(EXPECTED_WINDOW_START, EXPECTED_WINDOW_END, 0, BATCH_SIZE))
        .thenReturn(List.of(5, 6, 7));
    when(writer.turnAvailabilityOn(anyList(), any())).thenReturn(List.of(5, 6, 7));

    SpKitCohortBatch batch = service().flipNextBatch(window(), 0);

    assertThat(batch.getLastSpId()).isEqualTo(7);
    assertThat(batch.isExhausted()).isFalse();
  }

  @Test
  @DisplayName("only SPs that actually changed come back — they are who gets notified")
  void flippedIdsAreTheReCheckedSubsetNotTheCandidates() {
    // The writer re-checks on the primary and finds one already switched on — by an operator
    // between the read and the write, or by replica lag re-offering one this run flipped. Telling
    // that SP "your availability is now on" would be announcing a change this job did not make.
    when(reader.findEligibleSpIds(EXPECTED_WINDOW_START, EXPECTED_WINDOW_END, 0, BATCH_SIZE))
        .thenReturn(List.of(1, 2, 3));
    when(writer.turnAvailabilityOn(anyList(), any())).thenReturn(List.of(1, 3));

    SpKitCohortBatch batch = service().flipNextBatch(window(), 0);

    assertThat(batch.getCandidateCount()).isEqualTo(3);
    assertThat(batch.getSpIds()).containsExactly(1, 3);
    // The cursor still advances past the skipped SP, so the next page cannot re-offer it.
    assertThat(batch.getLastSpId()).isEqualTo(3);
  }

  @Test
  @DisplayName("the writer is stamped with the run's actionAt, not a fresh clock read")
  void writerGetsTheRunsActionTimestamp() {
    SpAvailabilityAutoOnWindow window = window();
    when(reader.findEligibleSpIds(any(), any(), anyInt(), anyInt())).thenReturn(List.of(1));
    when(writer.turnAvailabilityOn(List.of(1), window.getActionAt())).thenReturn(List.of(1));

    assertThat(service().flipNextBatch(window, 0).getSpIds()).containsExactly(1);
  }

  @Test
  @DisplayName("the payload carries counts and ids bounded by batch size, never by table size")
  void payloadIsBoundedByConfigurationNotData() {
    when(reader.findEligibleSpIds(any(), any(), anyInt(), anyInt())).thenReturn(List.of(1, 2, 3));
    when(writer.turnAvailabilityOn(anyList(), any())).thenReturn(List.of(1, 2, 3));

    SpKitCohortBatch batch = service().flipNextBatch(window(), 0);

    assertThat(batch.getSpIds()).hasSizeLessThanOrEqualTo(BATCH_SIZE);
    // One entry per named query, not one per batch or one per row (rule 15).
    assertThat(batch.getQueryDurationsMs())
        .containsOnlyKeys("findEligibleSpIds", "turnAvailabilityOn");
    assertThat(batch.getQueryRowCounts())
        .containsEntry("findEligibleSpIds", 3)
        .containsEntry("turnAvailabilityOn", 3);
  }

  @Test
  @DisplayName("the read that ends the run empty-handed is still timed")
  void emptyReadIsStillTimed() {
    when(reader.findEligibleSpIds(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

    SpKitCohortBatch batch = service().flipNextBatch(window(), 0);

    assertThat(batch.getQueryDurationsMs()).containsOnlyKeys("findEligibleSpIds");
    assertThat(batch.getQueryRowCounts()).containsEntry("findEligibleSpIds", 0);
  }

  @Test
  @DisplayName("a batch failure propagates rather than being reported as a clean empty batch")
  void writeFailurePropagates() {
    // The activity must fail so Temporal retries it. Swallowing this and returning an empty batch
    // would make the workflow treat the window as exhausted and stop, losing every SP after it.
    when(reader.findEligibleSpIds(any(), any(), anyInt(), anyInt())).thenReturn(List.of(1, 2, 3));
    when(writer.turnAvailabilityOn(anyList(), any()))
        .thenThrow(new IllegalStateException("connection lost"));

    assertThatThrownBy(() -> service().flipNextBatch(window(), 0))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a retry of the same cursor re-flips nothing the first attempt committed")
  void retryOfABatchIsANoOp() {
    // The eligibility query is self-consuming, so on the retry the reader simply does not return
    // the SPs the failed attempt already flipped.
    when(reader.findEligibleSpIds(EXPECTED_WINDOW_START, EXPECTED_WINDOW_END, 0, BATCH_SIZE))
        .thenReturn(List.of());

    SpKitCohortBatch batch = service().flipNextBatch(window(), 0);

    assertThat(batch.getSpIds()).isEmpty();
    verifyNoInteractions(writer);
  }
}
