package com.yesmadam.schedulerservice.jobs.cinntraPassbook.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.yesmadam.schedulerservice.jobs.cinntraPassbook.activity.CinntraPassbookSyncActivities;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookChunkResult;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSelection;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSyncKind;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

/**
 * The chunked-loop shape shared by the three passbook workflows ({@link CinntraPassbookSyncRunner}),
 * covered once through the journal workflow and once through a two-kind workflow — rule 12.
 */
class CinntraPassbookSyncWorkflowTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(
              CinntraPassbookJournalSyncWorkflowImpl.class,
              CinntraPassbookCommissionConvenienceSyncWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  /** Temporal needs a real class, not a Mockito mock. */
  static class StubActivities implements CinntraPassbookSyncActivities {
    Map<CinntraPassbookSyncKind, List<Long>> idsByKind = Map.of();
    CinntraPassbookSyncKind failSelectionFor;
    Integer failOnChunkIndex;
    final List<List<Long>> chunks = new ArrayList<>();
    final List<CinntraPassbookSyncKind> selected = new ArrayList<>();

    @Override
    public CinntraPassbookSelection selectPassbookEntries(CinntraPassbookSyncKind kind, long nominalFireTimeMillis) {
      selected.add(kind);
      if (kind == failSelectionFor) {
        throw ApplicationFailure.newNonRetryableFailure("replica down", "TestFailure");
      }
      return new CinntraPassbookSelection(idsByKind.getOrDefault(kind, List.of()), "s", "e", Map.of(), Map.of());
    }

    @Override
    public CinntraPassbookChunkResult syncPassbookChunk(CinntraPassbookSyncKind kind, List<Long> passbookIds) {
      int index = chunks.size();
      chunks.add(passbookIds);
      if (failOnChunkIndex != null && index == failOnChunkIndex) {
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      CinntraPassbookChunkResult result = new CinntraPassbookChunkResult();
      result.setSucceeded(passbookIds.size());
      return result;
    }
  }

  private static List<Long> ids(int count) {
    return LongStream.rangeClosed(1, count).boxed().collect(Collectors.toList());
  }

  private static <T> T stub(TestWorkflowEnvironment env, Worker worker, StubActivities activities, Class<T> type) {
    worker.registerActivitiesImplementations(activities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(type, WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  @Test
  void postsEveryIdOnceInChunksOfTwenty(TestWorkflowEnvironment env, Worker worker) {
    StubActivities activities = new StubActivities();
    activities.idsByKind = Map.of(CinntraPassbookSyncKind.JOURNAL, ids(45));

    stub(env, worker, activities, CinntraPassbookJournalSyncWorkflow.class).run();

    assertThat(activities.chunks).extracting(List::size).containsExactly(20, 20, 5);
    assertThat(activities.chunks.stream().flatMap(List::stream)).containsExactlyElementsOf(ids(45));
  }

  @Test
  void aFailedChunkDoesNotStopOrRepeatTheChunksAroundItButFailsTheRun(TestWorkflowEnvironment env, Worker worker) {
    StubActivities activities = new StubActivities();
    activities.idsByKind = Map.of(CinntraPassbookSyncKind.JOURNAL, ids(45));
    activities.failOnChunkIndex = 1;

    CinntraPassbookJournalSyncWorkflow workflow =
        stub(env, worker, activities, CinntraPassbookJournalSyncWorkflow.class);

    assertThatThrownBy(workflow::run).isInstanceOf(WorkflowFailedException.class);
    // Non-retryable in the stub, so each chunk ran exactly once: neighbours neither lost nor repeated.
    assertThat(activities.chunks).hasSize(3);
    assertThat(activities.chunks.get(0)).isEqualTo(ids(20));
    assertThat(activities.chunks.get(2)).isEqualTo(ids(45).subList(40, 45));
  }

  @Test
  void noIdsMeansNoChunkActivities(TestWorkflowEnvironment env, Worker worker) {
    StubActivities activities = new StubActivities();

    stub(env, worker, activities, CinntraPassbookJournalSyncWorkflow.class).run();

    assertThat(activities.chunks).isEmpty();
  }

  @Test
  void convenienceStillRunsWhenCommissionSelectionFails(TestWorkflowEnvironment env, Worker worker) {
    StubActivities activities = new StubActivities();
    activities.idsByKind = Map.of(CinntraPassbookSyncKind.CONVENIENCE, ids(3));
    activities.failSelectionFor = CinntraPassbookSyncKind.COMMISSION;

    CinntraPassbookCommissionConvenienceSyncWorkflow workflow =
        stub(env, worker, activities, CinntraPassbookCommissionConvenienceSyncWorkflow.class);

    assertThatThrownBy(workflow::run).isInstanceOf(WorkflowFailedException.class);
    assertThat(activities.selected)
        .containsExactly(CinntraPassbookSyncKind.COMMISSION, CinntraPassbookSyncKind.CONVENIENCE);
    assertThat(activities.chunks).containsExactly(ids(3));
  }
}
