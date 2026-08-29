package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest;
import com.yesmadam.schedulerservice.common.temporal.TemporalScheduleRegistrar;

import io.temporal.client.schedules.ScheduleClient;

/**
 * Covers what happens to the other jobs when one of them is misconfigured.
 *
 * <p>The earlier shape of this validated the whole file and aborted on the first problem, which
 * meant a single typo in one class name stopped the other hundred jobs from picking up their
 * changes. That is the wrong blast radius for a startup reconcile: the deploy looks successful, the
 * log has one line in it, and nothing anyone changed actually took effect.
 */
class ScheduleReconcilerTest {

  private static final String VALID_WORKFLOW =
      "com.yesmadam.schedulerservice.jobs.packageexpiry.workflow.PackageExpiryWorkflow";
  private static final String WORKFLOW_IMPL =
      "com.yesmadam.schedulerservice.jobs.packageexpiry.workflow.PackageExpiryWorkflowImpl";

  private TemporalScheduleRegistrar registrar;
  private JobScheduleProperties properties;
  private ScheduleReconciler reconciler;

  @BeforeEach
  void setUp() {
    registrar = mock(TemporalScheduleRegistrar.class);
    ScheduleClient client = mock(ScheduleClient.class);
    when(client.listSchedules()).thenReturn(Stream.empty());

    properties = new JobScheduleProperties();
    reconciler =
        new ScheduleReconciler(
            properties,
            registrar,
            client,
            "scheduler-light",
            "scheduler-standard",
            "scheduler-heavy",
            "scheduler-notification");
  }

  private static JobDefinition job(String name, String workflow, TaskQueueTier tier) {
    JobDefinition job = new JobDefinition();
    job.setName(name);
    job.setWorkflow(workflow);
    job.setTier(tier);
    job.setCron("0 2 * * *");
    return job;
  }

  private List<CronScheduleRequest> registeredRequests() {
    ArgumentCaptor<CronScheduleRequest> captor =
        ArgumentCaptor.forClass(CronScheduleRequest.class);
    verify(registrar, times(registrationCount())).createIfAbsent(captor.capture());
    return captor.getAllValues();
  }

  private int registrationCount() {
    return (int)
        org.mockito.Mockito.mockingDetails(registrar).getInvocations().stream()
            .filter(i -> "createIfAbsent".equals(i.getMethod().getName()))
            .count();
  }

  @Test
  @DisplayName("one invalid job does not stop the others from reconciling")
  void invalidJobDoesNotBlockTheRest() {
    properties.setJobs(
        List.of(
            job("good-one", VALID_WORKFLOW, TaskQueueTier.STANDARD),
            job("broken", "com.example.DoesNotExist", TaskQueueTier.HEAVY),
            job("good-two", VALID_WORKFLOW, TaskQueueTier.LIGHT)));

    reconciler.reconcileOnStartup();

    assertThat(registeredRequests())
        .extracting(CronScheduleRequest::getScheduleId)
        .containsExactlyInAnyOrder("good-one-schedule", "good-two-schedule")
        .doesNotContain("broken-schedule");
  }

  @Test
  @DisplayName("pointing at the impl instead of the interface is caught before Temporal sees it")
  void workflowImplIsRejected() {
    properties.setJobs(List.of(job("wrong-type", WORKFLOW_IMPL, TaskQueueTier.STANDARD)));

    reconciler.reconcileOnStartup();

    verify(registrar, never()).createIfAbsent(any());
  }

  @Test
  @DisplayName("a duplicate job name is rejected — two schedules would fire the same work twice")
  void duplicateNameIsRejected() {
    properties.setJobs(
        List.of(
            job("same-name", VALID_WORKFLOW, TaskQueueTier.STANDARD),
            job("same-name", VALID_WORKFLOW, TaskQueueTier.HEAVY)));

    reconciler.reconcileOnStartup();

    assertThat(registeredRequests()).hasSize(1);
  }

  @Test
  @DisplayName("the declared tier decides the task queue")
  void tierResolvesToQueue() {
    properties.setJobs(
        List.of(
            job("heavy-one", VALID_WORKFLOW, TaskQueueTier.HEAVY),
            job("light-one", VALID_WORKFLOW, TaskQueueTier.LIGHT)));

    reconciler.reconcileOnStartup();

    assertThat(registeredRequests())
        .extracting(CronScheduleRequest::getTaskQueue)
        .containsExactlyInAnyOrder("scheduler-heavy", "scheduler-light");
  }

  @Test
  @DisplayName("a Spring six-field cron is rejected rather than firing at the wrong time")
  void springCronIsRejected() {
    JobDefinition job = job("pasted-from-spring", VALID_WORKFLOW, TaskQueueTier.STANDARD);
    job.setCron("0 0 1 * * ?");

    properties.setJobs(List.of(job));
    reconciler.reconcileOnStartup();

    verify(registrar, never()).createIfAbsent(any());
  }

  @Test
  @DisplayName("a job with no name is reported without blowing up on the null")
  void unnamedJobIsReported() {
    JobDefinition unnamed = job(null, VALID_WORKFLOW, TaskQueueTier.STANDARD);

    properties.setJobs(List.of(unnamed, job("fine", VALID_WORKFLOW, TaskQueueTier.LIGHT)));
    reconciler.reconcileOnStartup();

    assertThat(registeredRequests())
        .extracting(CronScheduleRequest::getScheduleId)
        .containsExactly("fine-schedule");
  }

  @Test
  @DisplayName("an enabled job is created running, a disabled one paused")
  void enabledControlsInitialPausedState() {
    JobDefinition on = job("switched-on", VALID_WORKFLOW, TaskQueueTier.LIGHT);
    on.setEnabled(true);

    properties.setJobs(List.of(on, job("switched-off", VALID_WORKFLOW, TaskQueueTier.LIGHT)));
    reconciler.reconcileOnStartup();

    assertThat(registeredRequests())
        .filteredOn(r -> "switched-on-schedule".equals(r.getScheduleId()))
        .singleElement()
        .satisfies(r -> assertThat(r.getCreatePaused()).isFalse());
    assertThat(registeredRequests())
        .filteredOn(r -> "switched-off-schedule".equals(r.getScheduleId()))
        .singleElement()
        .satisfies(r -> assertThat(r.getCreatePaused()).isTrue());
  }

  @Test
  @DisplayName("an empty job list does not silently pass for a working service")
  void emptyJobListIsNotSilent() {
    properties.setJobs(List.of());

    reconciler.reconcileOnStartup();

    verify(registrar, never()).createIfAbsent(any());
  }

  @Test
  @DisplayName("reconciliation can be turned off entirely")
  void canBeDisabled() {
    properties.setReconcileOnStartup(false);
    properties.setJobs(List.of(job("any", VALID_WORKFLOW, TaskQueueTier.STANDARD)));

    reconciler.reconcileOnStartup();

    verify(registrar, never()).createIfAbsent(any());
  }
}
