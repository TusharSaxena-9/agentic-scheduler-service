package com.yesmadam.schedulerservice.jobs.roseorder.activity;

import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderChunkResult;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderEligibilityThresholds;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderRunContext;
import com.yesmadam.schedulerservice.jobs.roseorder.service.RoseOrderService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of rose order activities. Acts as a thin adapter between Temporal and the service
 * layer — all business logic lives in {@link RoseOrderService}.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.roseorder}")
public class RoseOrderActivitiesImpl implements RoseOrderActivities {

  private final RoseOrderService roseOrderService;

  public RoseOrderActivitiesImpl(RoseOrderService roseOrderService) {
    this.roseOrderService = roseOrderService;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link RoseOrderService#fetchRunContext()}.
   */
  @Override
  @Loggable(operation = "fetch-rose-order-run-context")
  @HandleExceptions
  public RoseOrderRunContext fetchRoseOrderRunContext() {
    return roseOrderService.fetchRunContext();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegates to {@link RoseOrderService#processChunk(List, RoseOrderEligibilityThresholds,
   * String)}. The workflow's run id is read here — via {@code Activity.getExecutionContext()},
   * the only place it's legal to read it from an activity thread — rather than in {@link
   * RoseOrderService}, which stays free of any Temporal-specific types.
   */
  @Override
  @Loggable(operation = "process-rose-order-chunk")
  @HandleExceptions
  public RoseOrderChunkResult processRoseOrderChunk(
      List<Integer> spIds, RoseOrderEligibilityThresholds thresholds) {
    String runId = Activity.getExecutionContext().getInfo().getRunId();
    return roseOrderService.processChunk(spIds, thresholds, runId);
  }
}
