package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.SpAvailabilityRepository;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.SpUpdateActionHistoryRepository;

/**
 * The writer's job is to keep the flip and its audit row in step. These tests pin that: the history
 * describes what the re-check said would change, never the batch as handed in.
 */
@ExtendWith(MockitoExtension.class)
class SpAvailabilityAutoOnWriterTest {

  private static final Timestamp ACTION_AT = Timestamp.valueOf("2026-09-16 00:30:00.0");

  @Mock SpAvailabilityRepository availabilityRepository;
  @Mock SpUpdateActionHistoryRepository historyRepository;
  @InjectMocks SpAvailabilityAutoOnWriter writer;

  @Test
  @DisplayName("an empty batch touches neither repository")
  void emptyBatchShortCircuits() {
    assertThat(writer.turnAvailabilityOn(List.of(), ACTION_AT)).isEmpty();

    verifyNoInteractions(availabilityRepository, historyRepository);
  }

  @Test
  @DisplayName("a batch the re-check rejects entirely writes no history")
  void batchAlreadyFlippedWritesNothing() {
    // The retry-after-commit case from rule 14, and the replica-lag case: the ids came from the
    // replica but the primary says they are already on. Nothing to update, nothing to record.
    when(availabilityRepository.findStillAvailabilityOff(List.of(1, 2))).thenReturn(List.of());

    assertThat(writer.turnAvailabilityOn(List.of(1, 2), ACTION_AT)).isEmpty();

    verify(availabilityRepository, never()).turnAvailabilityOn(anyList(), any());
    verifyNoInteractions(historyRepository);
  }

  @Test
  @DisplayName("history is written for the re-checked subset, not the batch as handed in")
  void historyFollowsTheReCheckNotTheCandidates() {
    when(availabilityRepository.findStillAvailabilityOff(List.of(1, 2, 3)))
        .thenReturn(List.of(1, 3)); // 2 was switched on by someone else in the meantime
    when(availabilityRepository.turnAvailabilityOn(List.of(1, 3), ACTION_AT)).thenReturn(2);

    assertThat(writer.turnAvailabilityOn(List.of(1, 2, 3), ACTION_AT)).containsExactly(1, 3);

    ArgumentCaptor<List<SpUpdateActionHistory>> rows = ArgumentCaptor.forClass(List.class);
    verify(historyRepository).saveAll(rows.capture());
    assertThat(rows.getValue()).extracting(SpUpdateActionHistory::getSpId).containsExactly(1, 3);
  }

  @Test
  @DisplayName("each history row records the transition, the timestamp and no human actor")
  void historyRowShape() {
    when(availabilityRepository.findStillAvailabilityOff(List.of(7))).thenReturn(List.of(7));
    when(availabilityRepository.turnAvailabilityOn(eq(List.of(7)), eq(ACTION_AT))).thenReturn(1);

    writer.turnAvailabilityOn(List.of(7), ACTION_AT);

    ArgumentCaptor<List<SpUpdateActionHistory>> rows = ArgumentCaptor.forClass(List.class);
    verify(historyRepository).saveAll(rows.capture());
    assertThat(rows.getValue())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getSpId()).isEqualTo(7);
              assertThat(row.getPrevious()).isEqualTo("false");
              assertThat(row.getCurrent()).isEqualTo("true");
              assertThat(row.getAction()).isEqualTo(SpKitApprovalActions.AVAILABILITY_TURNED_ON);
              assertThat(row.getActionAt()).isEqualTo(ACTION_AT);
              // No employee did this; attributing it to one would name a real person on the
              // SP's timeline for a change they did not make.
              assertThat(row.getActionBy()).isNull();
            });
  }

  @Test
  @DisplayName("the action name stays distinct from the manual toggle and from the notify marker")
  void actionNameIsDistinct() {
    // Collide with USER_AVAILABILITY and an operator reading an SP's timeline can no longer tell a
    // nightly automated flip from someone deciding to switch the SP on. Collide with the notify
    // marker and the 20:00 job's dedup filter starts matching the 23:30 job's rows, which would
    // silently suppress notifications.
    assertThat(SpKitApprovalActions.AVAILABILITY_TURNED_ON)
        .isNotEqualTo("USER_AVAILABILITY")
        .isNotEqualTo(SpKitApprovalActions.KIT_APPROVED_NOTIFIED);
  }
}
