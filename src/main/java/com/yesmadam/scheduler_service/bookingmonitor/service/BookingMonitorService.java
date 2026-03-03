package com.yesmadam.scheduler_service.bookingmonitor.service;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.yesmadam.scheduler_service.bookingmonitor.model.BookingPlatformCount;
import com.yesmadam.scheduler_service.bookingmonitor.repository.BookingMonitorRepository;
import com.yesmadam.scheduler_service.common.whatsapp.service.WhatsappAlertService;

import lombok.extern.slf4j.Slf4j;

/**
 * Service containing business logic for the booking monitor workflow. Handles fetching booking
 * counts and dispatching WhatsApp alerts.
 */
@Slf4j
@Service
public class BookingMonitorService {

  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final BookingMonitorRepository bookingMonitorRepository;
  private final WhatsappAlertService whatsappAlertService;

  public BookingMonitorService(
      BookingMonitorRepository bookingMonitorRepository,
      WhatsappAlertService whatsappAlertService) {
    this.bookingMonitorRepository = bookingMonitorRepository;
    this.whatsappAlertService = whatsappAlertService;
  }

  /**
   * Fetches booking counts grouped by platform for the given time window. Returns a map of platform
   * name to count.
   *
   * @param start start of the booking window
   * @param end end of the booking window
   * @return map of platform name to booking count
   */
  public Map<String, Integer> fetchBookingCounts(Timestamp start, Timestamp end) {
    List<BookingPlatformCount> results =
        bookingMonitorRepository.findBookingCountByPlatform(start, end);
    return results.stream()
        .collect(
            Collectors.toMap(BookingPlatformCount::getPlatform, BookingPlatformCount::getCount));
  }

  /**
   * Calculates the Android booking count from the platform counts map. Combines both "Android App"
   * and "android app" key variants.
   *
   * @param platformCounts map of platform name to count
   * @return total Android booking count
   */
  public int calculateAndroidCount(Map<String, Integer> platformCounts) {
    return platformCounts.getOrDefault("Android App", 0)
        + platformCounts.getOrDefault("android app", 0);
  }

  /**
   * Calculates the iOS booking count from the platform counts map. Combines both "IOS APP" and "ios
   * app" key variants.
   *
   * @param platformCounts map of platform name to count
   * @return total iOS booking count
   */
  public int calculateIosCount(Map<String, Integer> platformCounts) {
    return platformCounts.getOrDefault("IOS APP", 0) + platformCounts.getOrDefault("ios app", 0);
  }

  /**
   * Sends WhatsApp alerts to all configured phone numbers for any platform with zero bookings in
   * the monitoring window.
   *
   * @param phoneNumbers list of recipient phone numbers
   * @param templateName WhatsApp template name to use
   * @param androidCount Android booking count
   * @param iosCount iOS booking count
   * @param windowStart start of the monitoring window
   * @param windowEnd end of the monitoring window
   */
  public void sendAlertsIfNeeded(
      List<String> phoneNumbers,
      String templateName,
      int androidCount,
      int iosCount,
      Timestamp windowStart,
      Timestamp windowEnd) {
    if (phoneNumbers == null || phoneNumbers.isEmpty()) {
      log.info("event=whatsapp_alerts_skipped reason=no_phone_numbers");
      return;
    }

    if (androidCount > 0 && iosCount > 0) {
      log.info(
          "event=whatsapp_alerts_skipped reason=counts_non_zero android={} ios={}",
          androidCount,
          iosCount);
      return;
    }

    String timeRange = buildTimeRange(windowStart, windowEnd);

    for (String phoneNumber : phoneNumbers) {
      sendAlertForPhoneNumber(phoneNumber, templateName, androidCount, iosCount, timeRange);
    }
  }

  /**
   * Sends the appropriate alert to a single phone number based on which platform has zero bookings.
   */
  private void sendAlertForPhoneNumber(
      String phoneNumber, String templateName, int androidCount, int iosCount, String timeRange) {
    if (androidCount == 0 && iosCount == 0) {
      whatsappAlertService.sendWhatsappSms(
          phoneNumber,
          templateName,
          Arrays.asList("0", timeRange, "Android App & IOS APP(9AM to 11.59PM)"));
    } else if (androidCount == 0) {
      whatsappAlertService.sendWhatsappSms(
          phoneNumber, templateName, Arrays.asList("0", timeRange, "Android App(9AM to 11.59PM)"));
    } else {
      whatsappAlertService.sendWhatsappSms(
          phoneNumber, templateName, Arrays.asList("0", timeRange, "IOS APP(9AM to 11.59PM)"));
    }
  }

  /**
   * Builds a human-readable time range string for use in WhatsApp alert messages.
   *
   * @param windowStart start of the window
   * @param windowEnd end of the window
   * @return formatted string e.g. "2 min (09:00:00 to 09:02:00)"
   */
  private String buildTimeRange(Timestamp windowStart, Timestamp windowEnd) {
    String from = windowStart.toLocalDateTime().toLocalTime().withNano(0).format(TIME_FORMATTER);
    String to = windowEnd.toLocalDateTime().toLocalTime().withNano(0).format(TIME_FORMATTER);
    return "2 min (" + from + " to " + to + ")";
  }
}
