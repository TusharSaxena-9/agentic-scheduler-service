package com.yesmadam.scheduler_service.bookingmonitor.activity;

import java.sql.Timestamp;
import java.util.Map;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activity interface for booking monitor business logic. Handles fetching booking counts and
 * sending WhatsApp alerts.
 */
@ActivityInterface
public interface BookingMonitorActivities {

  /**
   * Fetches booking counts grouped by platform for the given time window.
   *
   * @param start start of the booking window
   * @param end end of the booking window
   * @return map of platform name to booking count
   */
  @ActivityMethod
  Map<String, Integer> fetchBookingCounts(Timestamp start, Timestamp end);

  /**
   * Sends WhatsApp alerts if any platform has zero bookings in the window. Phone numbers and
   * template name are read from application properties.
   *
   * @param androidCount Android booking count
   * @param iosCount iOS booking count
   * @param windowStart start of the monitoring window
   * @param windowEnd end of the monitoring window
   */
  @ActivityMethod
  void sendAlertsIfNeeded(
      int androidCount, int iosCount, Timestamp windowStart, Timestamp windowEnd);
}
