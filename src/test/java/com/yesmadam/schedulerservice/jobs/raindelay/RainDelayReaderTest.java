package com.yesmadam.schedulerservice.jobs.raindelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;

import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the booking_date pre-filter bounds. It is redundant for correctness and easy to "tidy away",
 * but it is the only predicate that can drive an index on tbl_order — and its margin is what keeps
 * a midnight-crossing slot from being dropped.
 */
@ExtendWith(MockitoExtension.class)
class RainDelayReaderTest {

  @Mock RainDelayOrderRepository repository;
  @InjectMocks RainDelayReader reader;

  @Test
  @DisplayName("candidate query bounds booking_date a day wider than the window on each side")
  void candidateQueryWidensBookingDate() {
    LocalDateTime start = LocalDateTime.of(2026, 9, 22, 23, 30);
    LocalDateTime end = start.plusHours(1);

    reader.findCandidateHubs(List.of(4), start, end);

    ArgumentCaptor<Date> lower = ArgumentCaptor.forClass(Date.class);
    ArgumentCaptor<Date> upper = ArgumentCaptor.forClass(Date.class);
    verify(repository)
        .findRainCheckHubsWithImminentBookings(
            anyList(), lower.capture(), upper.capture(), any(), any());

    // A 23:30 run spills into the 23rd. Lower reaches back to the 21st because a late slot that
    // crosses midnight can carry the previous day's booking_date; upper is exclusive.
    assertThat(lower.getValue().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 21));
    assertThat(upper.getValue().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 24));
  }

  @Test
  @DisplayName("target query uses the same bounds as the candidate query")
  void targetQueryUsesSameBounds() {
    LocalDateTime start = LocalDateTime.of(2026, 9, 22, 14, 0);
    LocalDateTime end = start.plusHours(1);

    reader.findNotificationTargets(List.of(4), start, end);

    ArgumentCaptor<Date> lower = ArgumentCaptor.forClass(Date.class);
    ArgumentCaptor<Date> upper = ArgumentCaptor.forClass(Date.class);
    verify(repository)
        .findNotificationTargets(anyList(), lower.capture(), upper.capture(), any(), any());

    // Must match the candidate query: a hub is selected on its status-3 bookings in this range.
    assertThat(lower.getValue().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 21));
    assertThat(upper.getValue().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 23));
  }
}
