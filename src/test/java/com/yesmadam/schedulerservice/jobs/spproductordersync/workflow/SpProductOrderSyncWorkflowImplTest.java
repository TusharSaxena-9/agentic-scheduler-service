package com.yesmadam.schedulerservice.jobs.spproductordersync.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.yesmadam.schedulerservice.jobs.spproductordersync.activity.SpProductOrderSyncActivities;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

/**
 * Covers this workflow's orchestration shape — two sequential activities, the first non-fatal on
 * failure and the second fatal — which no other job's workflow test exercises yet (CLAUDE.md rule
 * 12: one test per shape, not per job).
 */
class SpProductOrderSyncWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(SpProductOrderSyncWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  // ================================================================
  // Stub implementation — Temporal requires a real class, not a Mockito mock
  // ================================================================

  static class StubSpProductOrderSyncActivities implements SpProductOrderSyncActivities {
    List<String> callOrder = new ArrayList<>();
    int inventoryRowsToReturn = 5;
    int syncRowsToReturn = 5;
    boolean failIncrement = false;
    boolean failSync = false;

    @Override
    public int incrementInventoryProductsPurchasedForDelivered() {
      callOrder.add("increment");
      if (failIncrement) {
        // Non-retryable so Temporal stops retrying immediately in tests
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      return inventoryRowsToReturn;
    }

    @Override
    public int syncDeliveredSpUnavailableProducts() {
      callOrder.add("sync");
      if (failSync) {
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      return syncRowsToReturn;
    }
  }

  // ================================================================
  // Helpers
  // ================================================================

  private SpProductOrderSyncWorkflow buildWorkflow(
      TestWorkflowEnvironment env, Worker worker, StubSpProductOrderSyncActivities activities) {
    worker.registerActivitiesImplementations(activities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            SpProductOrderSyncWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  // ================================================================
  // Happy path — increment runs before sync
  // ================================================================

  @Test
  void run_shouldIncrementInventoryBeforeSyncingAvailability(
      TestWorkflowEnvironment env, Worker worker) {
    StubSpProductOrderSyncActivities activities = new StubSpProductOrderSyncActivities();

    buildWorkflow(env, worker, activities).run();

    assertThat(activities.callOrder).containsExactly("increment", "sync");
  }

  // ================================================================
  // Increment failure — non-fatal, sync step still runs
  // ================================================================

  @Test
  void run_shouldStillSyncIfIncrementFails(TestWorkflowEnvironment env, Worker worker) {
    StubSpProductOrderSyncActivities activities = new StubSpProductOrderSyncActivities();
    activities.failIncrement = true;

    buildWorkflow(env, worker, activities).run();

    assertThat(activities.callOrder).containsExactly("increment", "sync");
  }

  // ================================================================
  // Sync failure — fatal, fails the workflow
  // ================================================================

  @Test
  void run_shouldFailWorkflowIfSyncFails(TestWorkflowEnvironment env, Worker worker) {
    StubSpProductOrderSyncActivities activities = new StubSpProductOrderSyncActivities();
    activities.failSync = true;

    assertThatThrownBy(() -> buildWorkflow(env, worker, activities).run())
        .isInstanceOf(WorkflowFailedException.class);

    assertThat(activities.callOrder).containsExactly("increment", "sync");
  }
}
