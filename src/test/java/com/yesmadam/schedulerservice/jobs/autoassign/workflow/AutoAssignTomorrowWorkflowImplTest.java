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
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;

/**
 * The D-1 workflow type shares all its orchestration with the D0 one, so this covers only what is
 * different: that it supplies {@code NEXT_DAY}, and that the scope reaches both the context fetch
 * and every chunk.
 *
 * <p>That second half is the one worth pinning. The scope decides three things — the booking date,
 * whether a lead-time floor applies, and whether the SP-rejection bypass is evaluated — and a scope
 * that reached only the fetch would silently run D-1 candidates through the D0 gate.
 */
class AutoAssignTomorrowWorkflowImplTest {

  @RegisterExtension
  static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(AutoAssignTomorrowWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  static class ScopeRecordingActivities implements AutoAssignActivities {
    final List<AutoAssignScope> scopesSeen = new ArrayList<>();
    List<AutoAssignCandidate> candidates = Collections.emptyList();

    @Override
    public AutoAssignRunContext fetchAutoAssignRunContext(AutoAssignScope scope) {
      scopesSeen.add(scope);
      return new AutoAssignRunContext(
          "2026-09-02", candidates, new AutoAssignThresholds(2, 60, 60, 45, 15), scope);
    }

    @Override
    public AutoAssignChunkResult processAutoAssignChunk(
        List<AutoAssignCandidate> chunk, AutoAssignThresholds thresholds, AutoAssignScope scope) {
      scopesSeen.add(scope);
      Map<AutoAssignOutcome, Integer> counts = new EnumMap<>(AutoAssignOutcome.class);
      counts.put(AutoAssignOutcome.ASSIGN_TRIGGERED, chunk.size());
      return new AutoAssignChunkResult(chunk.size(), counts, Collections.emptyList());
    }

    @Override
    public DistributionTriggerResult dispatchDistributionBatch(List<Long> orderIds) {
      return DistributionTriggerResult.empty();
    }
  }

  static class NoOpCronTimingActivities implements CronTimingActivities {
    @Override
    public Long saveCronStart(String javaFileName, String methodName, String remark) {
      return 1L;
    }

    @Override
    public void saveCronEnd(Long cronTimingId) {
      // no-op
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
      return 1L;
    }
  }

  @Test
  void run_shouldUseNextDayScopeForTheFetchAndEveryChunk(
      TestWorkflowEnvironment env, Worker worker) {
    ScopeRecordingActivities activities = new ScopeRecordingActivities();
    activities.candidates =
        IntStream.rangeClosed(1, 250)
            .mapToObj(i -> new AutoAssignCandidate((long) i, null))
            .collect(Collectors.toList());

    worker.registerActivitiesImplementations(activities, new NoOpCronTimingActivities());
    env.start();

    AutoAssignTomorrowWorkflow workflow =
        env.getWorkflowClient()
            .newWorkflowStub(
                AutoAssignTomorrowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());
    workflow.run();

    // 1 context fetch + 10 chunks of 25 (CHUNK_SIZE), every one NEXT_DAY.
    assertThat(activities.scopesSeen).hasSize(11);
    assertThat(activities.scopesSeen).containsOnly(AutoAssignScope.NEXT_DAY);
  }
}
