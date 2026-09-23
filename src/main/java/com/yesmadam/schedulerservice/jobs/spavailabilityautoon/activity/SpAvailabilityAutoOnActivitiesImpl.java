package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.activity;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service.SpAvailabilityAutoOnService;

import io.temporal.spring.boot.ActivityImpl;

/**
 * Thin adapter between Temporal and the service layer.
 *
 * <p>This job sends nothing — the CleverTap heads-up went out at 20:00 from {@code
 * SpKitApprovedNotifyActivitiesImpl}, on the same cohort. All this one does is flip availability
 * and record what it flipped.
 */
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpAvailabilityAutoOnActivitiesImpl implements SpAvailabilityAutoOnActivities {

  /**
   * Explicit rather than a no-arg {@code LocalDate.now()} (CLAUDE.md rule 1). The pod sets {@code
   * TZ}, but that lives in the Kubernetes manifest — one edit away from moving every date bucket
   * this job produces, and absent entirely on a developer machine. The schedule's own firing zone
   * is a separate setting ({@code temporal.schedule.defaults.time-zone}); both are IST, and both
   * have to be, for "yesterday" to mean the same day in each — and for the 20:00 and 23:30 jobs to
   * agree on which SPs they are both talking about.
   */
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SpAvailabilityAutoOnService service;

  public SpAvailabilityAutoOnActivitiesImpl(SpAvailabilityAutoOnService service) {
    this.service = service;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "resolve-sp-availability-auto-on-window")
  @HandleExceptions
  public SpAvailabilityAutoOnWindow resolveRunWindow() {
    return service.resolveWindow(LocalDate.now(IST));
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "flip-sp-availability-batch")
  @HandleExceptions
  public SpKitCohortBatch flipNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId) {
    return service.flipNextBatch(window, afterSpId);
  }
}
