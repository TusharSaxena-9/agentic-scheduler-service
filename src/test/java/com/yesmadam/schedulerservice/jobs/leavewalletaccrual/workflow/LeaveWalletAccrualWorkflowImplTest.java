package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.workflow;

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
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.activity.LeaveWalletAccrualActivities;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.LeaveWalletAccrualRunContext;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

class LeaveWalletAccrualWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(LeaveWalletAccrualWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  // ================================================================
  // Stub implementations — Temporal requires real classes not Mockito mocks
  // ================================================================

  static class StubLeaveWalletAccrualActivities implements LeaveWalletAccrualActivities {
    List<Integer> spIdsToReturn = Collections.emptyList();
    List<Integer> spaSpIds = Collections.emptyList();
    BigDecimal standardWoMonthly = new BigDecimal("6");
    BigDecimal spaHydraSdlMonthly = new BigDecimal("1");
    List<List<Integer>> processedChunks = new ArrayList<>();
    Integer failOnChunkIndex = null;

    @Override
    public LeaveWalletAccrualRunContext fetchLeaveWalletAccrualRunContext() {
      return new LeaveWalletAccrualRunContext(
          spIdsToReturn, spaSpIds, standardWoMonthly, spaHydraSdlMonthly);
    }

    @Override
    public int accrueMonthlyLeaveChunk(
        List<Integer> chunkSpIds,
        List<Integer> spaSpIds,
        BigDecimal standardWoMonthly,
        BigDecimal spaHydraSdlMonthly) {
      if (failOnChunkIndex != null && processedChunks.size() == failOnChunkIndex) {
        // Non-retryable so Temporal stops retrying immediately in tests
        throw ApplicationFailure.newNonRetryableFailure("DB failure", "TestFailure");
      }
      processedChunks.add(chunkSpIds);
      return chunkSpIds.size();
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

  private LeaveWalletAccrualWorkflow buildWorkflow(
      TestWorkflowEnvironment env,
      Worker worker,
      StubLeaveWalletAccrualActivities activities,
      StubCronTimingActivities cronActivities) {
    worker.registerActivitiesImplementations(activities, cronActivities);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            LeaveWalletAccrualWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
  }

  // ================================================================
  // Happy path — wallet table processed in chunks of 100
  // ================================================================

  @Test
  void run_shouldProcessWalletInChunks(TestWorkflowEnvironment env, Worker worker) {
    StubLeaveWalletAccrualActivities activities = new StubLeaveWalletAccrualActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.spIdsToReturn = spIds(1000);

    buildWorkflow(env, worker, activities, cronActivities).run();

    assertThat(activities.processedChunks).hasSize(3);
    assertThat(activities.processedChunks.get(0)).hasSize(400);
    assertThat(activities.processedChunks.get(1)).hasSize(400);
    assertThat(activities.processedChunks.get(2)).hasSize(200);
    assertThat(cronActivities.savedEndId).isEqualTo(1L);
  }

  // ================================================================
  // Empty wallet table — no chunk activities at all
  // ================================================================

  @Test
  void run_shouldSkipChunkProcessingIfNoSpIds(TestWorkflowEnvironment env, Worker worker) {
    StubLeaveWalletAccrualActivities activities = new StubLeaveWalletAccrualActivities();
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
    StubLeaveWalletAccrualActivities activities = new StubLeaveWalletAccrualActivities();
    StubCronTimingActivities cronActivities = new StubCronTimingActivities();

    activities.spIdsToReturn = spIds(1000);
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
