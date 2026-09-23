package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.activity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.common.clevertap.exception.CleverTapException;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service.SpAvailabilityAutoOnService;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service.SpAvailabilityNotifier;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service.SpKitApprovedNotifyService;

import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;

/**
 * Thin adapter between Temporal and the service layer for the 20:00 notification job.
 *
 * <p>Runs on {@code light}, including the CleverTap send. Rule 6 suggests routing outbound sends to
 * {@code notification} — but that queue name is only known as a Spring-resolved placeholder, and
 * workflow code can neither use {@code @Value} nor read the environment (rule 1), so the {@code
 * setTaskQueue(...)} override would have to hardcode the literal and drift silently if the queue
 * were renamed. {@code ProductHubLiveStockAlertActivitiesImpl} hit the same wall and made the same
 * call; the send still gets the outbound-send timeout shape via a per-method override in the
 * workflow, which is the part that matters.
 */
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpKitApprovedNotifyActivitiesImpl implements SpKitApprovedNotifyActivities {

  /**
   * Explicit rather than a no-arg {@code LocalDate.now()} (rule 1). The pod sets {@code TZ}, but
   * that lives in the Kubernetes manifest — one edit from moving every date bucket, and absent on a
   * developer machine. Must match the zone the 23:30 job resolves in, or the two act on different
   * cohorts.
   */
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SpAvailabilityAutoOnService windowService;
  private final SpKitApprovedNotifyService notifyService;
  private final SpAvailabilityNotifier notifier;

  public SpKitApprovedNotifyActivitiesImpl(
      SpAvailabilityAutoOnService windowService,
      SpKitApprovedNotifyService notifyService,
      SpAvailabilityNotifier notifier) {
    this.windowService = windowService;
    this.notifyService = notifyService;
    this.notifier = notifier;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "resolve-sp-kit-approved-notify-window")
  @HandleExceptions
  public SpAvailabilityAutoOnWindow resolveRunWindowNotification() {
    return windowService.resolveWindowNotification(LocalDate.now(IST));
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "claim-sp-kit-approved-notify-batch")
  @HandleExceptions
  public SpKitCohortBatch claimNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId) {
    return notifyService.claimNextBatch(window, afterSpId);
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "notify-sp-kit-approved")
  @HandleExceptions
  public int notifyKitApproved(List<Integer> spIds) {
    try {
      return notifier.notifyAvailabilityTurnedOn(spIds);
    } catch (CleverTapException e) {
      if (e.isPermanent()) {
        throw ApplicationFailure.newNonRetryableFailureWithCause(
            e.getMessage(), "CleverTapPermanentFailure", e);
      }
      throw e;
    }
  }
}
