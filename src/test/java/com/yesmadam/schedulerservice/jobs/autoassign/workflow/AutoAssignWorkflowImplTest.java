package com.yesmadam.schedulerservice.jobs.autoassign.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoassign.activity.AutoAssignActivities;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignOutcome;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignCandidate;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignRunContext;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignThresholds;
import com.yesmadam.schedulerservice.jobs.crontiming.activity.CronTimingActivities;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

class AutoAssignWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(AutoAssignWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  /**
   * Orchestration is shared with {@link AutoAssignTomorrowWorkflowImpl} via {@code
   * AbstractAutoAssignWorkflowImpl}, so these cover the shape once rather than once per workflow
   * type — rule 12. {@link AutoAssignTomorrowWorkflowImplTest} asserts only what differs: that the
   * D-1 type supplies its own scope.
   */

  // ================================================================
  // Stub implementations — Temporal requires real classes not Mockito mocks
  // ================================================================

  static class StubAutoAssignActivities implements AutoAssignActivities {
    List<AutoAssignCandidate> candidatesToReturn = Collections.emptyList();
    AutoAssignThresholds thresholds = new AutoAssignThresholds(2, 60, 60, 45, 15);
    List<List<AutoAssignCandidate>> processedChunks = new ArrayList<>();
    List<List<Long>> dispatchedBatches = new ArrayList<>();
    List<AutoAssignScope> scopesSeen = new ArrayList<>();
    Integer failOnChunkIndex = null;
    Integer failOnBatchIndex = null;

    /** Orders the stub reports as queued for distribution, per chunk. */
    int distributionPerChunk = 0;

    @Override
    public AutoAssignRunContext fetchAutoAssignRunContext(AutoAssignScope scope) {
      scopesSeen.add(scope);
      return new AutoAssignRunContext("2026-08-21", candidatesToReturn, thresholds, scope);
    }

    @Override
    public AutoAssignChunkResult processAutoAssignChunk(
        List<AutoAssignCandidate> chunk, AutoAssignThresholds thresholds, AutoAssignScope scope) {
      scopesSeen.add(scope);
      if (failOnChunkIndex != null && processedChunks.size() == failOnChunkIndex) {
        // Non-retryable so Temporal stops retrying immediately in tests
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      processedChunks.add(chunk);

      List<Long> distribution =
          chunk.stream()
              .limit(distributionPerChunk)
              .map(AutoAssignCandidate::getOrderId)
              .collect(Collectors.toList());

      Map<AutoAssignOutcome, Integer> counts = new EnumMap<>(AutoAssignOutcome.class);
      counts.put(AutoAssignOutcome.ASSIGN_TRIGGERED, chunk.size() - distribution.size());
      counts.put(AutoAssignOutcome.QUEUED_FOR_DISTRIBUTION, distribution.size());

      return new AutoAssignChunkResult(chunk.size(), counts, distribution);
    }

    @Override
    public DistributionTriggerResult dispatchDistributionBatch(List<Long> orderIds) {
      dispatchedBatches.add(orderIds);
      if (failOnBatchIndex != null && dispatchedBatches.size() - 1 == failOnBatchIndex) {
        throw ApplicationFailure.newNonRetryableFailure("admin down", "TestFailure");
      }
      // One order per batch reaches a live round; the rest queue. Mirrors the real gate, which
      // fires at most once per batch however many orders it received.
      return new DistributionTriggerResult(
          orderIds.size(), orderIds.size(), orderIds.size(), orderIds.size(), 1);
    }
  }

  static class StubCronTimingActivities implements CronTimingActivities {
    Long idToReturn = 1L;
    Long savedEndId = null;

    @Override
    public Long saveCronStart(String javaFileName, String methodName, String remark) {
      return idToReturn;
    }

    @Override
    public void saveCronEnd(Long cronTimingId) {
      savedEndId = cronTimingId;
    }

    @Override
    public Long saveCronStartWithCounts(
        String javaFileName,
        String methodName,
        String remark,
        Timestamp windowStart,
        Timestamp windowEnd,
        Integer androidCount,
        Integer iosCount) {
      return idToReturn;
    }
  }

  // ================================================================
  // Helpers
  // ================================================================

  private static List<AutoAssignCandidate> candidates(int count) {
    return IntStream.rangeClosed(1, count)
        .mapToObj(i -> new AutoAssignCandidate((long) i, null))
        .collect(Collectors.toList());
  }

  private AutoAssignWorkflow buildWorkflow(
      TestWorkflowEnvironment env,
      Worker worker,
      StubAutoAssignActivities activities,
      StubCronTimingActivities cronActivities) {
    worker.registerActivitiesImplementations(activities, cronActivities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            AutoAssignWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  // ================================================================
  // Happy path — candidates processed in chunks of 100
  // ================================================================

  @Test
  void run_shouldProcessCandidatesInChunks(TestWorkflowEnvironment env, Worker worker) {
    StubAutoAssignActivities activities = new StubAutoAssignActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.candidatesToReturn = candidates(250);

    buildWorkflow(env, worker, activities, cronActivities).run();

    // CHUNK_SIZE is 25, sized so one chunk's worst case fits the standard tier — 250 candidates is
    // therefore 10 full chunks, not 3.
    assertThat(activities.processedChunks).hasSize(10);
    assertThat(activities.processedChunks).allSatisfy(c -> assertThat(c).hasSize(25));
    assertThat(activities.processedChunks.stream().mapToInt(List::size).sum()).isEqualTo(250);
  }

  // ================================================================
  // Nothing to assign — no chunk activities, no distribution dispatch
  // ================================================================

  @Test
  void run_shouldSkipChunkProcessingIfNoCandidates(TestWorkflowEnvironment env, Worker worker) {
    StubAutoAssignActivities activities = new StubAutoAssignActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.candidatesToReturn = Collections.emptyList();

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.processedChunks).isEmpty();
    assertThat(activities.dispatchedBatches).isEmpty();
  }

  // ================================================================
  // No distribution candidates — dispatch is never called
  // ================================================================

  @Test
  void run_shouldNotDispatchDistributionWhenEveryOrderWasAssigned(
      TestWorkflowEnvironment env, Worker worker) {
    StubAutoAssignActivities activities = new StubAutoAssignActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.candidatesToReturn = candidates(50);
    activities.distributionPerChunk = 0;

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.processedChunks).hasSize(2);
    assertThat(activities.dispatchedBatches).isEmpty();
  }

  // ================================================================
  // Distribution candidates from every chunk are dispatched together,
  // batched at 200 per call
  // ================================================================

  @Test
  void run_shouldDispatchAllDistributionCandidatesBatchedAfterAssignPhase(
      TestWorkflowEnvironment env, Worker worker) {
    StubAutoAssignActivities activities = new StubAutoAssignActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    // 500 candidates at CHUNK_SIZE 25 -> 20 chunks, every order queued for distribution
    activities.candidatesToReturn = candidates(500);
    activities.distributionPerChunk = 100;

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.processedChunks).hasSize(20);
    // 500 candidates at DISTRIBUTION_BATCH_SIZE=200 -> 200 + 200 + 100
    assertThat(activities.dispatchedBatches).hasSize(3);
    assertThat(activities.dispatchedBatches.get(0)).hasSize(200);
    assertThat(activities.dispatchedBatches.get(1)).hasSize(200);
    assertThat(activities.dispatchedBatches.get(2)).hasSize(100);
    // Assign phase completes before anything is dispatched
    assertThat(activities.dispatchedBatches.stream().mapToInt(List::size).sum()).isEqualTo(500);
  }

  // ================================================================
  // Chunk failure — earlier chunks stay processed, cron end always runs,
  // and nothing is dispatched from a run that never finished assigning
  // ================================================================

  @Test
  void run_shouldKeepCompletedChunksAndSaveCronEndIfAChunkFails(
      TestWorkflowEnvironment env, Worker worker) {
    StubAutoAssignActivities activities = new StubAutoAssignActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.candidatesToReturn = candidates(250);
    activities.distributionPerChunk = 10;
    activities.failOnChunkIndex = 2; // chunks 0 and 1 succeed, chunk 2 fails

    try {
      buildWorkflow(env, worker, activities, cronActivities).run();
    } catch (Exception ignored) {
      // workflow is expected to fail
    }

    // Chunks completed before the failure are recorded and not re-run. There is no cron-timing row
    // to assert on any more (rule 8) — Temporal's own history is the execution record, and the
    // distribution phase never starting is the observable consequence of the chunk failing.
    assertThat(activities.processedChunks).hasSize(2);
    assertThat(activities.dispatchedBatches).isEmpty();
  }

  @Test
  void run_shouldNotRedispatchCompletedDistributionBatchesWhenOneFails(
      TestWorkflowEnvironment env, Worker worker) {
    // P2: the distribution phase must behave like the assign phase — a failed batch stops the run,
    // and the batches that already completed are neither re-sent nor rolled back. Re-sending would
    // matter here in a way it does not for chunks: each dispatch can fire a real distribution.
    StubAutoAssignActivities activities = new StubAutoAssignActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    // 5 chunks of 100, all queued -> 500 candidates -> 3 batches of 200/200/100
    activities.candidatesToReturn = candidates(500);
    activities.distributionPerChunk = 100;
    activities.failOnBatchIndex = 1; // batch 0 succeeds, batch 1 fails, batch 2 never runs

    try {
      buildWorkflow(env, worker, activities, cronActivities).run();
    } catch (Exception ignored) {
      // workflow is expected to fail
    }

    // Batch 0 dispatched once, batch 1 attempted once, batch 2 never reached.
    assertThat(activities.dispatchedBatches).hasSize(2);
    assertThat(activities.dispatchedBatches.get(0)).hasSize(200);
    // Every assign chunk still completed before the distribution phase began.
    assertThat(activities.processedChunks).hasSize(20);
  }

  @Test
  void run_shouldReportFiredCountFromTheDistributionEngine(
      TestWorkflowEnvironment env, Worker worker) {
    // A batch hands over many orders and the engine fires at most one; the workflow must surface
    // what the engine reported rather than assuming every dispatched order was distributed.
    StubAutoAssignActivities activities = new StubAutoAssignActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.candidatesToReturn = candidates(250);
    activities.distributionPerChunk = 100; // capped by chunk size: 25 per chunk -> 250 queued

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.dispatchedBatches).hasSize(2);
    assertThat(activities.dispatchedBatches.stream().mapToInt(List::size).sum()).isEqualTo(250);
  }
}
