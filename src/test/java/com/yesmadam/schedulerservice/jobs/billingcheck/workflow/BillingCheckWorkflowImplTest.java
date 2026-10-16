package com.yesmadam.schedulerservice.jobs.billingcheck.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.yesmadam.schedulerservice.jobs.billingcheck.activity.BillingCheckActivities;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckChunk;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckOrder;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckSendResult;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

class BillingCheckWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(BillingCheckWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  /** Scripted pages, one per fetch. */
  static class ScriptedActivities implements BillingCheckActivities {
    final List<BillingCheckChunk> pages = new ArrayList<>();
    final List<Long> cursorsSeen = new ArrayList<>();
    final List<Long> nominalsSeen = new ArrayList<>();
    final List<BillingCheckChunk> sendCalls = new ArrayList<>();
    int sendAttempts = 0;
    boolean failSendOnce = false;

    @Override
    public BillingCheckChunk fetchChunk(long nominalFireTimeMillis, long afterOrderId) {
      cursorsSeen.add(afterOrderId);
      nominalsSeen.add(nominalFireTimeMillis);
      return pages.get(cursorsSeen.size() - 1);
    }

    @Override
    public BillingCheckSendResult sendChunk(BillingCheckChunk chunk) {
      sendAttempts++;
      if (failSendOnce) {
        throw ApplicationFailure.newFailure("provider down mid-chunk", "TestSendFailure");
      }
      sendCalls.add(chunk);
      return new BillingCheckSendResult(chunk.getOrders().size(), 0);
    }
  }

  private static BillingCheckChunk page(long cursor, boolean done, long... orderIds) {
    BillingCheckChunk c = new BillingCheckChunk();
    c.setNextCursor(cursor);
    c.setDone(done);
    List<BillingCheckOrder> orders = new ArrayList<>();
    for (long id : orderIds) {
      orders.add(new BillingCheckOrder(id, 9999900000L + id, List.of("a", "b", "1", "d")));
    }
    c.setOrders(orders);
    return c;
  }

  private BillingCheckWorkflow start(TestWorkflowEnvironment env, Worker worker, ScriptedActivities a) {
    worker.registerActivitiesImplementations(a);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            BillingCheckWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  @Test
  void run_shouldFetchAndSendEachPageThenStopWhenDone(TestWorkflowEnvironment env, Worker worker) {
    ScriptedActivities a = new ScriptedActivities();
    a.pages.add(page(2, false, 1, 2));
    a.pages.add(page(3, true, 3));

    start(env, worker, a).run();

    assertThat(a.cursorsSeen).containsExactly(0L, 2L); // cursor carried from page to page
    assertThat(a.sendCalls).hasSize(2);
    assertThat(a.nominalsSeen).containsOnly(a.nominalsSeen.get(0)); // one window for the whole run
  }

  @Test
  void run_shouldSkipTheSendWhenAPageIsEmpty(TestWorkflowEnvironment env, Worker worker) {
    ScriptedActivities a = new ScriptedActivities();
    a.pages.add(page(0, true));

    start(env, worker, a).run();

    assertThat(a.sendAttempts).isZero();
  }

  @Test
  void run_shouldNeverRetryASendThatFailedMidChunk(TestWorkflowEnvironment env, Worker worker) {
    // Sends run once: a retry would re-message customers already reached.
    ScriptedActivities a = new ScriptedActivities();
    a.failSendOnce = true;
    a.pages.add(page(2, true, 1, 2));

    assertThatThrownBy(() -> start(env, worker, a).run()).isInstanceOf(WorkflowFailedException.class);

    assertThat(a.sendAttempts).isEqualTo(1);
  }

  @Test
  void run_shouldFailRatherThanWedgeIfTheCursorNeverReachesTheEnd(
      TestWorkflowEnvironment env, Worker worker) {
    ScriptedActivities a =
        new ScriptedActivities() {
          @Override
          public BillingCheckChunk fetchChunk(long nominal, long after) {
            return page(after, false); // never done, never advances
          }
        };

    assertThatThrownBy(() -> start(env, worker, a).run()).isInstanceOf(WorkflowFailedException.class);
  }
}
