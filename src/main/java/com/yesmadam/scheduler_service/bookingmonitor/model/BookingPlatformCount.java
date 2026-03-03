package com.yesmadam.scheduler_service.bookingmonitor.model;

/**
 * Projection interface for booking count query results grouped by platform. Maps to the result of
 * the native SQL query in BookingMonitorRepository.
 */
public interface BookingPlatformCount {

  /** Returns the platform name (e.g. "Android App", "IOS APP"). */
  String getPlatform();

  /** Returns the number of bookings for this platform in the queried window. */
  Integer getCount();
}
