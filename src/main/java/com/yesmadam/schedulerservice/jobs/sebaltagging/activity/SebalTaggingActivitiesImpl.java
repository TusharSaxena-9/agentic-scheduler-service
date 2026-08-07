package com.yesmadam.schedulerservice.jobs.sebaltagging.activity;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.DateRangeChunk;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingChunkResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingRunContext;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingTierResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.service.SebalTaggingService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of SEBAL tagging activities. Acts as a thin adapter between Temporal and the
 * service layer — all business logic lives in {@link SebalTaggingService}.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.sebaltagging}")
public class SebalTaggingActivitiesImpl implements SebalTaggingActivities {

  private final SebalTaggingService sebalTaggingService;

  public SebalTaggingActivitiesImpl(SebalTaggingService sebalTaggingService) {
    this.sebalTaggingService = sebalTaggingService;
  }

  @Override
  @Loggable(operation = "fetch-sebal-tagging-run-context")
  @HandleExceptions
  public SebalTaggingRunContext fetchSebalTaggingRunContext() {
    return sebalTaggingService.fetchRunContext();
  }

  /**
   * {@inheritDoc}
   *
   * <p>The workflow's run id is read here — via {@code Activity.getExecutionContext()}, the only
   * place it's legal to read it from an activity thread — rather than in {@link
   * SebalTaggingService}, which stays free of any Temporal-specific types.
   */
  @Override
  @Loggable(operation = "process-sebal-tagging-chunk")
  @HandleExceptions
  public SebalTaggingChunkResult processSebalTaggingChunk(
      DateRangeChunk chunk, SebalTaggingRunMode runMode) {
    String runId = Activity.getExecutionContext().getInfo().getRunId();
    return sebalTaggingService.processChunk(chunk, runMode, runId, heartbeat());
  }

  @Override
  @Loggable(operation = "finalize-sebal-tagging-run")
  @HandleExceptions
  public SebalTaggingTierResult finalizeSebalTaggingRun(
      SebalTaggingRunMode runMode, LocalDate newProcessedUntil) {
    String runId = Activity.getExecutionContext().getInfo().getRunId();
    return sebalTaggingService.finalizeRun(runMode, runId, newProcessedUntil, heartbeat());
  }

  private static Runnable heartbeat() {
    return () -> Activity.getExecutionContext().heartbeat(null);
  }
}
