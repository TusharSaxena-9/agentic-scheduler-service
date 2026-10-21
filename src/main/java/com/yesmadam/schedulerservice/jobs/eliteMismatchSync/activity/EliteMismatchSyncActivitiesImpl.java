package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.activity;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncChunkResult;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncRunContext;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.service.EliteMismatchSyncService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter between Temporal and the service layer — all logic lives in {@link
 * EliteMismatchSyncService}. The only Temporal-specific work here is turning the service's
 * progress reports into heartbeats.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class EliteMismatchSyncActivitiesImpl implements EliteMismatchSyncActivities {

  private final EliteMismatchSyncService eliteMismatchSyncService;

  public EliteMismatchSyncActivitiesImpl(EliteMismatchSyncService eliteMismatchSyncService) {
    this.eliteMismatchSyncService = eliteMismatchSyncService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "prepare-elite-mismatch-run")
  @HandleExceptions
  public EliteMismatchSyncRunContext prepareEliteMismatchRun() {
    return eliteMismatchSyncService.prepareRun();
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "sync-elite-mismatch-chunk")
  @HandleExceptions
  public EliteMismatchSyncChunkResult syncEliteMismatchChunk(
      List<Integer> subscriptionIds,
      Map<Integer, Integer> eliteFeeBySubscriptionId,
      Integer fallbackPlanAmount) {
    return eliteMismatchSyncService.syncChunk(
        subscriptionIds, eliteFeeBySubscriptionId, fallbackPlanAmount, heartbeat());
  }

  private static ProgressReporter heartbeat() {
    return completed -> Activity.getExecutionContext().heartbeat(completed);
  }
}
