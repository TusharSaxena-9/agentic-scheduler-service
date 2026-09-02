package com.yesmadam.schedulerservice.jobs.autoassign.activity;

import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionTriggerResult;
import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignCandidate;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignChunkResult;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignRunContext;
import com.yesmadam.schedulerservice.jobs.autoassign.model.AutoAssignThresholds;
import com.yesmadam.schedulerservice.jobs.autoassign.service.AutoAssignService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of auto-assign activities. Acts as a thin adapter between Temporal and the service
 * layer — all business logic lives in {@link AutoAssignService}.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class AutoAssignActivitiesImpl implements AutoAssignActivities {

  private final AutoAssignService autoAssignService;

  public AutoAssignActivitiesImpl(AutoAssignService autoAssignService) {
    this.autoAssignService = autoAssignService;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link AutoAssignService#fetchRunContext(AutoAssignScope)}.
   */
  @Override
  @Loggable(operation = "fetch-auto-assign-run-context")
  @HandleExceptions
  public AutoAssignRunContext fetchAutoAssignRunContext(AutoAssignScope scope) {
    return autoAssignService.fetchRunContext(scope);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link AutoAssignService#processChunk(List, AutoAssignThresholds,
   * AutoAssignScope)}.
   */
  @Override
  @Loggable(operation = "process-auto-assign-chunk")
  @HandleExceptions
  public AutoAssignChunkResult processAutoAssignChunk(
      List<AutoAssignCandidate> chunk, AutoAssignThresholds thresholds, AutoAssignScope scope) {
    // The heartbeat lives here rather than in the service: only the activity thread can call it,
    // and the service layer must stay free of io.temporal so it is testable with plain JUnit.
    return autoAssignService.processChunk(
        chunk, thresholds, scope, completed -> Activity.getExecutionContext().heartbeat(completed));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link AutoAssignService#dispatchDistributionBatch(List)}.
   */
  @Override
  @Loggable(operation = "dispatch-distribution-batch")
  @HandleExceptions
  public DistributionTriggerResult dispatchDistributionBatch(List<Long> orderIds) {
    return autoAssignService.dispatchDistributionBatch(orderIds);
  }
}
