package com.yesmadam.schedulerservice.common.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.yesmadam.schedulerservice.jobs.packageexpiry.workflow.PackageExpiryWorkflow;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleOptions;

/**
 * Covers registration, which is create-only: an existing schedule is left exactly as it is.
 *
 * <p>The memo matters more than it looks. It is the only marker distinguishing a schedule this
 * service created from one made by hand, and orphan reporting relies on it — without it a schedule
 * removed from {@code schedules.yml} is indistinguishable from one somebody created deliberately in
 * the UI.
 */
class TemporalScheduleRegistrarTest {

  private static final String SCHEDULE_ID = "package-expiry-schedule";

  private ScheduleClient client;
  private ScheduleHandle handle;
  private TemporalScheduleRegistrar registrar;

  @BeforeEach
  void setUp() {
    client = mock(ScheduleClient.class);
    handle = mock(ScheduleHandle.class);
    when(client.getHandle(SCHEDULE_ID)).thenReturn(handle);

    registrar =
        new TemporalScheduleRegistrar(
            client,
            "Asia/Kolkata",
            ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP,
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofHours(1),
            false,
            true);
  }

  private CronScheduleRequest request(String taskQueue) {
    return CronScheduleRequest.builder()
        .scheduleId(SCHEDULE_ID)
        .workflowId("package-expiry-workflow")
        .workflowType(PackageExpiryWorkflow.class)
        .taskQueue(taskQueue)
        .cronExpression("10 0 * * *")
        .build();
  }

  @Test
  @DisplayName("an existing schedule is left untouched")
  void leavesExistingScheduleAlone() {
    when(client.createSchedule(anyString(), any(), any(ScheduleOptions.class)))
        .thenThrow(new ScheduleAlreadyRunningException(new RuntimeException("already exists")));

    registrar.createIfAbsent(request("scheduler-standard"));

    verify(handle, never()).update(any());
    verify(handle, never()).describe();
  }

  @Test
  @DisplayName("a schedule that does not exist is created, and marked as ours")
  void createsWhenAbsent() {
    when(client.createSchedule(anyString(), any(), any(ScheduleOptions.class))).thenReturn(handle);

    registrar.createIfAbsent(request("scheduler-standard"));

    ArgumentCaptor<ScheduleOptions> options = ArgumentCaptor.forClass(ScheduleOptions.class);
    verify(client).createSchedule(anyString(), any(Schedule.class), options.capture());
    assertThat(options.getValue().getMemo())
        .containsEntry(
            TemporalScheduleRegistrar.MANAGED_BY_MEMO_KEY,
            TemporalScheduleRegistrar.MANAGED_BY_MEMO_VALUE);
    verify(handle, never()).update(any());
  }
}
