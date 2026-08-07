package com.yesmadam.schedulerservice.jobs.roseorder.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.yesmadam.schedulerservice.jobs.crontiming.activity.CronTimingActivities;
import com.yesmadam.schedulerservice.jobs.roseorder.activity.RoseOrderActivities;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderChunkResult;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderEligibilityThresholds;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderRunContext;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

class RoseOrderEligibleSpPoolWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(RoseOrderEligibleSpPoolWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  // ================================================================
  // Stub implementations — Temporal requires real classes not Mockito mocks
  // ================================================================

  static class StubRoseOrderActivities implements RoseOrderActivities {
    List<Integer> spIdsToReturn = Collections.emptyList();
    RoseOrderEligibilityThresholds thresholds =
        new RoseOrderEligibilityThresholds(new BigDecimal("85"), new BigDecimal("4.5"), 100);
    List<List<Integer>> processedChunks = new ArrayList<>();
    Integer failOnChunkIndex = null;

    @Override
    public RoseOrderRunContext fetchRoseOrderRunContext() {
      return new RoseOrderRunContext(spIdsToReturn, thresholds);
    }

    @Override
    public RoseOrderChunkResult processRoseOrderChunk(
        List<Integer> spIds, RoseOrderEligibilityThresholds thresholds) {
      if (failOnChunkIndex != null && processedChunks.size() == failOnChunkIndex) {
        // Non-retryable so Temporal stops retrying immediately in tests
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      processedChunks.add(spIds);
      return new RoseOrderChunkResult(spIds.size(), 1);
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

  private static List<Integer> spIds(int count) {
    return IntStream.rangeClosed(1, count).boxed().collect(Collectors.toList());
  }

  private RoseOrderEligibleSpPoolWorkflow buildWorkflow(
      TestWorkflowEnvironment env,
      Worker worker,
      StubRoseOrderActivities activities,
      StubCronTimingActivities cronActivities) {
    worker.registerActivitiesImplementations(activities, cronActivities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            RoseOrderEligibleSpPoolWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  // ================================================================
  // Happy path — pool processed in chunks of 100
  // ================================================================

  @Test
  void run_shouldProcessPoolInChunks(TestWorkflowEnvironment env, Worker worker) {
    StubRoseOrderActivities activities = new StubRoseOrderActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.spIdsToReturn = spIds(250);

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.processedChunks).hasSize(3);
    assertThat(activities.processedChunks.get(0)).hasSize(100);
    assertThat(activities.processedChunks.get(1)).hasSize(100);
    assertThat(activities.processedChunks.get(2)).hasSize(50);
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }

  // ================================================================
  // Empty pool — no chunk activities at all
  // ================================================================

  @Test
  void run_shouldSkipChunkProcessingIfNoActiveSps(TestWorkflowEnvironment env, Worker worker) {
    StubRoseOrderActivities activities = new StubRoseOrderActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.spIdsToReturn = Collections.emptyList();

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.processedChunks).isEmpty();
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }

  // ================================================================
  // Chunk failure — earlier chunks stay processed, cron end always runs
  // ================================================================

  @Test
  void run_shouldKeepCompletedChunksAndSaveCronEndIfAChunkFails(
      TestWorkflowEnvironment env, Worker worker) {
    StubRoseOrderActivities activities = new StubRoseOrderActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.spIdsToReturn = spIds(250);
    activities.failOnChunkIndex = 2; // chunks 0 and 1 succeed, chunk 2 fails

    try {
      buildWorkflow(env, worker, activities, cronActivities).run();
    } catch (Exception ignored) {
    }

    // Chunks completed before the failure are recorded and not re-run
    assertThat(activities.processedChunks).hasSize(2);
    // Most critical assertion — saveCronEnd must always run even if workflow fails
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }
}
