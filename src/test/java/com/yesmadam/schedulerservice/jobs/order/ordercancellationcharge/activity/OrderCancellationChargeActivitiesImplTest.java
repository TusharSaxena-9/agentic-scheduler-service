package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.model.OrderCancellationChargeExpiryResult;
import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.service.OrderCancellationChargeService;

@ExtendWith(MockitoExtension.class)
class OrderCancellationChargeActivitiesImplTest {

  @Mock OrderCancellationChargeService service;
  @InjectMocks OrderCancellationChargeActivitiesImpl activities;

  @Test
  void expireOverdueCancellationCharges_shouldReturnABoundedResultNotTheUpdatedRows() {
    when(service.expireOverdue(any())).thenReturn(7);

    OrderCancellationChargeExpiryResult result = activities.expireOverdueCancellationCharges();

    // The payload is exactly {cutoff, count} — no entity list crosses the activity boundary
    // (CLAUDE.md rule 9).
    assertThat(result.getUpdatedCount()).isEqualTo(7);
    assertThat(result.getCutoff()).isNotNull();
    verify(service).expireOverdue(result.getCutoff());
  }

  @Test
  void expireOverdueCancellationCharges_shouldReturnZeroCountWhenNothingIsOverdue() {
    when(service.expireOverdue(any())).thenReturn(0);

    assertThat(activities.expireOverdueCancellationCharges().getUpdatedCount()).isZero();
  }
}
