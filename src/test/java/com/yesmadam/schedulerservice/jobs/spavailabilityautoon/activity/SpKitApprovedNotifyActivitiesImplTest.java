package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.common.clevertap.exception.CleverTapException;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service.SpAvailabilityAutoOnService;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service.SpAvailabilityNotifier;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service.SpKitApprovedNotifyService;

import io.temporal.failure.ApplicationFailure;

/**
 * The adapter is thin, but the one decision it makes is worth pinning: which CleverTap failures are
 * worth retrying. Getting it backwards means either three attempts and their backoff spent on a 401
 * that was permanent from the first millisecond, or a transient blip treated as fatal.
 */
@ExtendWith(MockitoExtension.class)
class SpKitApprovedNotifyActivitiesImplTest {

  @Mock SpAvailabilityAutoOnService windowService;
  @Mock SpKitApprovedNotifyService notifyService;
  @Mock SpAvailabilityNotifier notifier;

  private SpKitApprovedNotifyActivitiesImpl activities() {
    return new SpKitApprovedNotifyActivitiesImpl(windowService, notifyService, notifier);
  }

  @Test
  @DisplayName("a successful send reports how many events went out")
  void successPassesThrough() {
    when(notifier.notifyAvailabilityTurnedOn(List.of(1, 2))).thenReturn(2);

    assertThat(activities().notifyKitApproved(List.of(1, 2))).isEqualTo(2);
  }

  @Test
  @DisplayName("a 4xx is re-thrown as a non-retryable ApplicationFailure")
  void permanentFailureIsClassifiedNonRetryable() {
    when(notifier.notifyAvailabilityTurnedOn(anyList()))
        .thenThrow(new CleverTapException("401 Unauthorized", 401));

    assertThatThrownBy(() -> activities().notifyKitApproved(List.of(1)))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(e -> assertThat(((ApplicationFailure) e).isNonRetryable()).isTrue());
  }

  @Test
  @DisplayName("a 5xx propagates unchanged so the SDK retries it")
  void transientFailurePropagatesForRetry() {
    when(notifier.notifyAvailabilityTurnedOn(anyList()))
        .thenThrow(new CleverTapException("503 Service Unavailable", 503));

    assertThatThrownBy(() -> activities().notifyKitApproved(List.of(1)))
        .isInstanceOf(CleverTapException.class);
  }
}
