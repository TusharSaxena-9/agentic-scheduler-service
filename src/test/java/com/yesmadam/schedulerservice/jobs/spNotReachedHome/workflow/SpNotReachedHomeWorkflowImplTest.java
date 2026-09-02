package com.yesmadam.schedulerservice.jobs.spNotReachedHome.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.yesmadam.schedulerservice.jobs.spNotReachedHome.activity.SpNotReachedHomeActivities;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

/**
 * Pins the orchestration SAAB-90 depends on: the three steps must run in this order, and a failure
 * in the first must not let the other two proceed.
 *
 * <p>Ordering is the substantive assertion. {@code removeSpWithActiveJob} has to precede {@code
 * insertSpNotReachedHome} — reversed, an SP who picked up a new job after 20:00 would have their
 * row refreshed by the UPSERT instead of deleted, and would be reported as not-home for the rest of
 * the evening.
 *
 * <p>There is no cron-timing assertion any more. The workflow no longer writes {@code
 * tbl_cron_time} (rule 8) — Temporal's own history is the execution record, and the per-step counts
 * the job reports are returned by the activities and land in that history.
 */
class SpNotReachedHomeWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(SpNotReachedHomeWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  static class StubActivities implements SpNotReachedHomeActivities {
    final List<String> callOrder = new ArrayList<>();
    boolean throwOnRemove = false;

    @Override
    public int removeSpWithActiveJob() {
      callOrder.add("remove");
      if (throwOnRemove) {
        // Non-retryable so the test does not wait out three attempts and their backoff.
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      return 2;
    }

    @Override
    public int insertSpNotReachedHome() {
      callOrder.add("insert");
      return 5;
    }

    @Override
    public int autoMarkReachedHome() {
      callOrder.add("autoMark");
      return 1;
    }
  }

  private SpNotReachedHomeWorkflow start(
      TestWorkflowEnvironment env, Worker worker, StubActivities activities) {
    worker.registerActivitiesImplementations(activities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            SpNotReachedHomeWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  @Test
  @DisplayName("the three steps run in the order the original sequenced them")
  void stepsRunInOrder(TestWorkflowEnvironment env, Worker worker) {
    StubActivities activities = new StubActivities();

    start(env, worker, activities).run();

    assertThat(activities.callOrder).containsExactly("remove", "insert", "autoMark");
  }

  @Test
  @DisplayName("a failure in step 1 stops the pass rather than skipping to steps 2 and 3")
  void failureInFirstStepStopsThePass(TestWorkflowEnvironment env, Worker worker) {
    // The original wrapped all three in one try/catch that swallowed everything, so a failure in
    // step 1 silently ran steps 2 and 3 against a stale panel. Here the failure propagates and the
    // remaining steps do not run — the next 5-minute tick is the retry.
    StubActivities activities = new StubActivities();
    activities.throwOnRemove = true;

    SpNotReachedHomeWorkflow workflow = start(env, worker, activities);

    assertThatThrownBy(workflow::run).isInstanceOf(Exception.class);
    assertThat(activities.callOrder).containsExactly("remove");
  }
}
