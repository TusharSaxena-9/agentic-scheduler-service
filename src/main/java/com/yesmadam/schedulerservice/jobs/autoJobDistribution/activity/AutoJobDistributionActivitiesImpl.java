package com.yesmadam.schedulerservice.jobs.autoJobDistribution.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPassResult;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.service.AutoJobDistributionService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/** Thin adapter between Temporal and {@link AutoJobDistributionService}. */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class AutoJobDistributionActivitiesImpl implements AutoJobDistributionActivities {

  private final AutoJobDistributionService autoJobDistributionService;

  public AutoJobDistributionActivitiesImpl(AutoJobDistributionService autoJobDistributionService) {
    this.autoJobDistributionService = autoJobDistributionService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "process-active-distributions")
  @HandleExceptions
  public DistributionPassResult processActiveDistributions() {
    // Heartbeat supplied here, not in the service: only the activity thread can call it, and the
    // service must stay free of io.temporal to remain unit-testable.
    return autoJobDistributionService.processActiveDistributions(
        completed -> Activity.getExecutionContext().heartbeat(completed));
  }
}
