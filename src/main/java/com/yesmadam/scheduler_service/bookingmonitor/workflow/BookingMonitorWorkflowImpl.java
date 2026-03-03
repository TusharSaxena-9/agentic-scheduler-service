package com.yesmadam.scheduler_service.bookingmonitor.workflow;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.bookingmonitor.activity.BookingMonitorActivities;
import com.yesmadam.scheduler_service.crontiming.activity.CronTimingActivities;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

/**
 * Workflow implementation for the booking monitor cron job. Runs every minute between 9AM and
 * 11:59PM IST. Queries booking counts for the last 2 minutes and sends WhatsApp alerts if Android
 * or iOS counts are zero.
 *
 * <p>Note: @Value annotations do not work in Temporal workflow classes as they are not managed by
 * Spring. All property injection must be done in activity implementations instead.
 */
@Slf4j
public class BookingMonitorWorkflowImpl implements BookingMonitorWorkflow {

  private final BookingMonitorActivities bookingMonitorActivities =
      Workflow.newActivityStub(
          BookingMonitorActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build());

  private final CronTimingActivities cronTimingActivities =
      Workflow.newActivityStub(
          CronTimingActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build());

  @Override
  @Loggable(operation = "booking-monitor-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    // Build 2-minute query window
    LocalDateTime now = LocalDateTime.now();
    Timestamp windowEnd = Timestamp.valueOf(now);
    Timestamp windowStart = Timestamp.valueOf(now.minusMinutes(2));

    // Step 1: Fetch booking counts for the last 2 minutes
    Map<String, Integer> platformCounts =
        bookingMonitorActivities.fetchBookingCounts(windowStart, windowEnd);

    int androidCount =
        platformCounts.getOrDefault("Android App", 0)
            + platformCounts.getOrDefault("android app", 0);
    int iosCount =
        platformCounts.getOrDefault("IOS APP", 0) + platformCounts.getOrDefault("ios app", 0);

    // Step 2: Save cron timing with platform counts
    Long cronTimingId =
        cronTimingActivities.saveCronStartWithCounts(
            "BookingMonitorWorkflow",
            "run",
            "Execute every 1 minute for last 2 min",
            windowStart,
            windowEnd,
            androidCount,
            iosCount);

    try {
      log.info(
          "event=workflow_started workflow_id={} android={} ios={}",
          workflowId,
          androidCount,
          iosCount);

      // Step 3: Send alerts if any platform has zero bookings
      // Phone numbers and template read from properties inside the activity
      bookingMonitorActivities.sendAlertsIfNeeded(androidCount, iosCount, windowStart, windowEnd);

    } finally {
      cronTimingActivities.saveCronEnd(cronTimingId);
    }
  }
}
