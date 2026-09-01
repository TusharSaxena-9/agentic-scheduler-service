package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.enumu.CancellationChargeStatus;
import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.repository.OrderCancellationChargeRepository;

@ExtendWith(MockitoExtension.class)
class OrderCancellationChargeServiceTest {

  private static final Timestamp CUTOFF = Timestamp.from(Instant.parse("2026-08-30T00:30:00Z"));

  @Mock OrderCancellationChargeRepository repository;
  @InjectMocks OrderCancellationChargeService service;

  @Test
  void expireOverdue_shouldReturnZeroWhenNothingMatches() {
    when(repository.expireOverdue(eq(CancellationChargeStatus.EXPIRED), anyList(), eq(CUTOFF)))
        .thenReturn(0);

    assertThat(service.expireOverdue(CUTOFF)).isZero();
  }

  @Test
  void expireOverdue_shouldPassCreatedAndChargedAsTheOverdueStatuses() {
    when(repository.expireOverdue(eq(CancellationChargeStatus.EXPIRED), anyList(), eq(CUTOFF)))
        .thenReturn(3);

    int updated = service.expireOverdue(CUTOFF);

    assertThat(updated).isEqualTo(3);
    ArgumentCaptor<List<CancellationChargeStatus>> statuses = ArgumentCaptor.forClass(List.class);
    verify(repository)
        .expireOverdue(eq(CancellationChargeStatus.EXPIRED), statuses.capture(), eq(CUTOFF));
    assertThat(statuses.getValue())
        .containsExactlyInAnyOrder(
            CancellationChargeStatus.CREATED, CancellationChargeStatus.CHARGED);
  }

  @Test
  void expireOverdue_secondCallAfterAFullExpiryShouldBeANoOp() {
    // Simulates a retry after the first call already committed: the repository's own WHERE clause
    // is what makes this convergent, but this pins the contract that a service with nothing left
    // to match returns 0 rather than erroring or re-counting already-expired rows.
    when(repository.expireOverdue(eq(CancellationChargeStatus.EXPIRED), anyList(), eq(CUTOFF)))
        .thenReturn(5)
        .thenReturn(0);

    assertThat(service.expireOverdue(CUTOFF)).isEqualTo(5);
    assertThat(service.expireOverdue(CUTOFF)).isZero();
  }
}
