package com.yesmadam.scheduler_service.booking.activity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.yesmadam.scheduler_service.annotation.HandleExceptions;
import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.booking.model.BookingCount;
import com.yesmadam.scheduler_service.booking.repository.BookingRepo;
import com.yesmadam.scheduler_service.logging.LoggingUtils;

import io.temporal.failure.ApplicationFailure;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BookingActivitiesImpl implements BookingActivities {

  private final BookingRepo bookingRepo;

  public BookingActivitiesImpl(BookingRepo bookingRepo) {
    this.bookingRepo = bookingRepo;
  }

  /**
   * Fetches all booking counts from the database.
   *
   * @return a list of all booking counts
   */
  @Override
  @Loggable(operation = "database-fetch-booking-counts")
  @HandleExceptions
  public List<BookingCount> fetchBookingCounts() {
    List<BookingCount> counts = bookingRepo.findAll();
    LoggingUtils.logPerformanceMetric("database-fetch-count", counts.size(), "records");
    return counts;
  }

  /**
   * Saves a list of booking counts to the database. Sets the local date time for each booking count
   * before saving.
   *
   * @param counts the list of booking counts to save
   */
  @Override
  @Loggable(operation = "database-save-booking-counts")
  @HandleExceptions
  public void saveBookingCounts(List<BookingCount> counts) {
    if (Objects.isNull(counts) || counts.isEmpty()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Booking counts list cannot be null or empty", "ILLEGAL_ARGUMENT");
    }

    counts.forEach(
        c -> {
          if (Objects.nonNull(c)) {
            c.setLocalDateTime(LocalDateTime.now());
          }
        });

    List<BookingCount> savedCounts = bookingRepo.saveAll(counts);
    LoggingUtils.logPerformanceMetric("database-save-count", savedCounts.size(), "records");
  }
}
