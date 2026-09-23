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
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

/** Service-layer tests for the 20:00 notify job (rule 12). */
@ExtendWith(MockitoExtension.class)
class SpKitApprovedNotifyServiceImplTest {

  private static final Timestamp WINDOW_START = Timestamp.valueOf("2026-09-15 00:00:00.0");
  private static final Timestamp WINDOW_END = Timestamp.valueOf("2026-09-15 23:59:59.0");
  private static final Timestamp ACTION_AT = Timestamp.valueOf("2026-09-16 20:00:00.0");

  private static final int BATCH_SIZE = 3;

  @Mock SpAvailabilityAutoOnReader reader;
  @Mock SpKitApprovedNotifyWriter writer;

  private SpKitApprovedNotifyServiceImpl service() {
    return new SpKitApprovedNotifyServiceImpl(reader, writer, BATCH_SIZE);
  }

  private SpAvailabilityAutoOnWindow window() {
    return new SpAvailabilityAutoOnWindow(WINDOW_START, WINDOW_END, ACTION_AT);
  }

  @Test
  @DisplayName("an empty page reports the window exhausted and claims nothing")
  void emptyPageShortCircuits() {
    when(reader.findUnnotifiedEligibleSpIds(any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());

    SpKitCohortBatch batch = service().claimNextBatch(window(), 0);

    assertThat(batch.getSpIds()).isEmpty();
    assertThat(batch.isExhausted()).isTrue();
    verifyNoInteractions(writer);
  }

  @Test
  @DisplayName("it reads the unnotified cohort, not the full eligible one")
  void readsTheUnnotifiedCohort() {
    when(reader.findUnnotifiedEligibleSpIds(WINDOW_START, WINDOW_END, 42, BATCH_SIZE))
        .thenReturn(List.of());

    service().claimNextBatch(window(), 42);

    // Using the flip job's query here would re-push every SP already notified this window.
    verifyNoInteractions(writer);
  }

  @Test
  @DisplayName("only newly claimed SPs come back — they are who gets pushed")
  void returnsTheClaimedSubset() {
    when(reader.findUnnotifiedEligibleSpIds(WINDOW_START, WINDOW_END, 0, BATCH_SIZE))
        .thenReturn(List.of(1, 2, 3));
    when(writer.claimForNotification(anyList(), any(), any())).thenReturn(List.of(1, 3));

    SpKitCohortBatch batch = service().claimNextBatch(window(), 0);

    assertThat(batch.getCandidateCount()).isEqualTo(3);
    assertThat(batch.getSpIds()).containsExactly(1, 3);
    // The cursor advances past the skipped SP, so the next page cannot re-offer it.
    assertThat(batch.getLastSpId()).isEqualTo(3);
    assertThat(batch.isExhausted()).isFalse();
  }

  @Test
  @DisplayName("a short page ends the run")
  void shortPageIsTheLastPage() {
    when(reader.findUnnotifiedEligibleSpIds(WINDOW_START, WINDOW_END, 0, BATCH_SIZE))
        .thenReturn(List.of(11, 22));
    when(writer.claimForNotification(anyList(), any(), any())).thenReturn(List.of(11, 22));

    assertThat(service().claimNextBatch(window(), 0).isExhausted()).isTrue();
  }

  @Test
  @DisplayName("the claim is bounded by the window start, and stamped with the run's actionAt")
  void writerGetsTheWindowsOwnTimestamps() {
    when(reader.findUnnotifiedEligibleSpIds(any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(1));
    when(writer.claimForNotification(eq(List.of(1)), eq(ACTION_AT), eq(WINDOW_START)))
        .thenReturn(List.of(1));

    assertThat(service().claimNextBatch(window(), 0).getSpIds()).containsExactly(1);
  }

  @Test
  @DisplayName("timings are recorded per named query, one entry each")
  void queryTimingsAreRecorded() {
    when(reader.findUnnotifiedEligibleSpIds(any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(1, 2));
    when(writer.claimForNotification(anyList(), any(), any())).thenReturn(List.of(1, 2));

    SpKitCohortBatch batch = service().claimNextBatch(window(), 0);

    // One entry per named query — these ride back in workflow history, so a key per row would be
    // an unbounded payload wearing a diagnostic's clothing (rule 9).
    assertThat(batch.getQueryDurationsMs())
        .containsOnlyKeys("findUnnotifiedEligibleSpIds", "claimForNotification");
    assertThat(batch.getQueryRowCounts())
        .containsEntry("findUnnotifiedEligibleSpIds", 2)
        .containsEntry("claimForNotification", 2);
  }

  @Test
  @DisplayName("a claim failure propagates rather than looking like an exhausted window")
  void claimFailurePropagates() {
    // Swallowing this and returning an empty batch would make the workflow stop early and silently
    // skip every SP after the failing page.
    when(reader.findUnnotifiedEligibleSpIds(any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(1, 2, 3));
    when(writer.claimForNotification(anyList(), any(), any()))
        .thenThrow(new IllegalStateException("connection lost"));

    assertThatThrownBy(() -> service().claimNextBatch(window(), 0))
        .isInstanceOf(IllegalStateException.class);
  }
}
