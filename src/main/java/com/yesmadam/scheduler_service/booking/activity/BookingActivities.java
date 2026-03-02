package com.yesmadam.scheduler_service.booking.activity;

import java.util.List;

import com.yesmadam.scheduler_service.booking.model.BookingCount;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface BookingActivities {
  /**
   * Fetches booking counts from the booking system.
   *
   * @return a list of booking counts
   */
  @ActivityMethod
  List<BookingCount> fetchBookingCounts();

  /**
   * Saves booking counts to the booking system.
   *
   * @param counts a list of booking counts to save
   */
  @ActivityMethod
  void saveBookingCounts(List<BookingCount> counts);
}
