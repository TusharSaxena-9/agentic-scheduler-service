package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.persistence.SpUpdateActionHistory;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.SpUpdateActionHistoryRepository;

/**
 * The claim is the only thing standing between a retried activity and a second push to every SP it
 * already notified, so what it returns has to be exactly what it marked — never the candidates it
 * was handed.
 */
@ExtendWith(MockitoExtension.class)
class SpKitApprovedNotifyWriterTest {

  private static final Timestamp ACTION_AT = Timestamp.valueOf("2026-09-16 20:00:00.0");
  private static final Timestamp WINDOW_START = Timestamp.valueOf("2026-09-15 00:00:00.0");

  @Mock SpUpdateActionHistoryRepository historyRepository;
  @InjectMocks SpKitApprovedNotifyWriter writer;

  @Test
  @DisplayName("an empty batch touches nothing")
  void emptyBatchShortCircuits() {
    assertThat(writer.claimForNotification(List.of(), ACTION_AT, WINDOW_START)).isEmpty();

    verifyNoInteractions(historyRepository);
  }

  @Test
  @DisplayName("a batch already claimed in full writes nothing and returns nothing")
  void fullyClaimedBatchIsANoOp() {
    // The retry-after-commit case: the replica had not caught up, so the reader re-offered SPs this
    // run already claimed. Returning them here would push each of them a second time.
    when(historyRepository.findSpIdsWithActionSince(anyList(), anyString(), any()))
        .thenReturn(List.of(1, 2));

    assertThat(writer.claimForNotification(List.of(1, 2), ACTION_AT, WINDOW_START)).isEmpty();

    verify(historyRepository, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("only the unclaimed subset is marked, and only that subset comes back to push")
  void returnsOnlyTheNewlyClaimed() {
    when(historyRepository.findSpIdsWithActionSince(anyList(), anyString(), any()))
        .thenReturn(List.of(2));

    assertThat(writer.claimForNotification(List.of(1, 2, 3), ACTION_AT, WINDOW_START))
        .containsExactly(1, 3);

    ArgumentCaptor<List<SpUpdateActionHistory>> rows = ArgumentCaptor.forClass(List.class);
    verify(historyRepository).saveAll(rows.capture());
    assertThat(rows.getValue()).extracting(SpUpdateActionHistory::getSpId).containsExactly(1, 3);
  }

  @Test
  @DisplayName("the marker records a notification, not a value change, and names no human actor")
  void markerRowShape() {
    when(historyRepository.findSpIdsWithActionSince(anyList(), anyString(), any()))
        .thenReturn(List.of());

    writer.claimForNotification(List.of(7), ACTION_AT, WINDOW_START);

    ArgumentCaptor<List<SpUpdateActionHistory>> rows = ArgumentCaptor.forClass(List.class);
    verify(historyRepository).saveAll(rows.capture());
    assertThat(rows.getValue())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getSpId()).isEqualTo(7);
              assertThat(row.getAction())
                  .isEqualTo(SpKitApprovalActions.KIT_APPROVED_NOTIFIED)
                  .isNotEqualTo(SpKitApprovalActions.AVAILABILITY_TURNED_ON);
              // false → false: nothing about the SP has changed yet, the flip is at 23:30.
              assertThat(row.getPrevious()).isEqualTo("false");
              assertThat(row.getCurrent()).isEqualTo("false");
              assertThat(row.getActionAt()).isEqualTo(ACTION_AT);
              assertThat(row.getActionBy()).isNull();
            });
  }

  @Test
  @DisplayName("the claimed-since bound is the window start, not the run time")
  void claimLookupIsBoundedByTheWindowNotTheClock() {
    // Bounding by windowStart is what lets an SP notified for an earlier approval be notified again
    // for a new one, while still suppressing a repeat inside the same window.
    when(historyRepository.findSpIdsWithActionSince(anyList(), anyString(), any()))
        .thenReturn(List.of());

    writer.claimForNotification(List.of(1), ACTION_AT, WINDOW_START);

    verify(historyRepository)
        .findSpIdsWithActionSince(
            List.of(1), SpKitApprovalActions.KIT_APPROVED_NOTIFIED, WINDOW_START);
  }

  @Test
  @DisplayName("the two jobs write distinct actions, so neither suppresses the other")
  void actionNamesAreDistinct() {
    assertThat(SpKitApprovalActions.KIT_APPROVED_NOTIFIED)
        .isNotEqualTo(SpKitApprovalActions.AVAILABILITY_TURNED_ON)
        .isNotEqualTo("USER_AVAILABILITY");
  }
}
