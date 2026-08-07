package com.yesmadam.schedulerservice.jobs.bookingmonitor.activity;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.bookingmonitor.service.BookingMonitorService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of booking monitor activities. Delegates all business logic to
 * BookingMonitorService. WhatsApp configuration is injected via @Value since activities are Spring
 * beans.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.bookingmonitor}")
public class BookingMonitorActivitiesImpl implements BookingMonitorActivities {

  @Value("${com.whatsapp.template.name}")
  private String whatsappTemplate;

  @Value(
      "#{'${com.whatsapp.alert.whatsappNumbers:}'.empty ? null : '${com.whatsapp.alert.whatsappNumbers:}'.split(',')}")
  private List<String> whatsappNumbers;

  private final BookingMonitorService bookingMonitorService;

  public BookingMonitorActivitiesImpl(BookingMonitorService bookingMonitorService) {
    this.bookingMonitorService = bookingMonitorService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "fetch-booking-counts")
  @HandleExceptions
  public Map<String, Integer> fetchBookingCounts(Timestamp start, Timestamp end) {
    return bookingMonitorService.fetchBookingCounts(start, end);
  }

  /**
   * Sends WhatsApp alerts if any platform has zero bookings. Phone numbers and template name are
   * read from application properties.
   *
   * <p>{@inheritDoc}
   */
  @Override
  @Loggable(operation = "send-whatsapp-alerts")
  @HandleExceptions
  public void sendAlertsIfNeeded(
      int androidCount, int iosCount, Timestamp windowStart, Timestamp windowEnd) {
    bookingMonitorService.sendAlertsIfNeeded(
        whatsappNumbers, whatsappTemplate,
        androidCount, iosCount,
        windowStart, windowEnd);
  }
}
