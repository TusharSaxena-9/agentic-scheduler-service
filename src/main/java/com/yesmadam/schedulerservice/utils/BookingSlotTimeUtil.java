package com.yesmadam.schedulerservice.utils;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves an order's absolute booking start instant from its {@code booking_date} plus the
 * free-text {@code booking_time} slot label (e.g. {@code "02:00 PM - 03:00 PM"}).
 *
 * <p>Ported from cron's {@code SlotsUtilities#checkTimeSlotFormatAndConvertIntoNewFormat} and
 * {@code JobDistributionConstants#startTimeMillis} so the auto-assign workflow applies the exact
 * same "minutes until the job starts" bar the cron applies today. Only the slot-start parsing is
 * carried over — the rest of {@code SlotsUtilities} is prime-time/slot-generation logic this
 * service has no use for.
 *
 * <p>{@code Locale.ENGLISH} is pinned on every formatter: the {@code a} (AM/PM) pattern is
 * locale-sensitive, so without it a literal {@code "AM"}/{@code "PM"} can fail to parse depending
 * on the JVM default locale.
 */
@Slf4j
public final class BookingSlotTimeUtil {

  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

  private static final DateTimeFormatter HOUR_ONLY_FORMATTER =
      DateTimeFormatter.ofPattern("hh a", Locale.ENGLISH);

  private static final DateTimeFormatter HOUR_ONLY_SINGLE_DIGIT_FORMATTER =
      DateTimeFormatter.ofPattern("h a", Locale.ENGLISH);

  /** Canonical slot label, e.g. {@code 02:00 PM}. */
  private static final String FORMAT_12H = "(((0[1-9])|(1[0-2])):([0-5])(0|3)\\s(A|P)M)";

  /** Legacy hour-only label, e.g. {@code 10 PM}. */
  private static final String FORMAT_12H_HOUR_ONLY = "(((0[1-9])|(1[0-2]))\\s(A|P)M)";

  /** Legacy hour-only label without the leading zero, e.g. {@code 2 PM}. */
  private static final String FORMAT_12H_HOUR_ONLY_SINGLE_DIGIT = "((([0-9]))\\s(A|P)M)";

  private BookingSlotTimeUtil() {}

  /**
   * Normalises the start half of a slot label to {@code hh:mm a}, tolerating the two legacy
   * hour-only formats still present in older rows.
   *
   * @param bookingSlot the raw {@code booking_time} value
   * @return the normalised slot start time
   */
  public static String slotStartTime(String bookingSlot) {
    String slotStartTime =
        bookingSlot.substring(0, bookingSlot.indexOf('-')).trim().toUpperCase(Locale.ENGLISH);

    if (slotStartTime.matches(FORMAT_12H)) {
      return slotStartTime;
    }
    if (slotStartTime.matches(FORMAT_12H_HOUR_ONLY)) {
      return LocalTime.parse(slotStartTime, HOUR_ONLY_FORMATTER).format(TIME_FORMATTER);
    }
    if (slotStartTime.matches(FORMAT_12H_HOUR_ONLY_SINGLE_DIGIT)) {
      return LocalTime.parse(slotStartTime, HOUR_ONLY_SINGLE_DIGIT_FORMATTER)
          .format(TIME_FORMATTER);
    }
    return slotStartTime;
  }

  /**
   * Absolute epoch-millis of the order's booking start, or {@code null} when the order has no
   * bookable start time or the slot label fails to parse.
   *
   * <p>Parse failures return {@code null} rather than throwing — matching the cron, where an
   * unparseable slot must never take the whole run down and instead falls through as "start time
   * unknown".
   *
   * @param bookingDate the order's {@code booking_date}
   * @param bookingTime the order's {@code booking_time} slot label
   * @return epoch-millis of the slot start, or {@code null} if it cannot be determined
   */
  public static Long startTimeMillis(Date bookingDate, String bookingTime) {
    if (bookingDate == null || bookingTime == null || bookingTime.isBlank()) {
      return null;
    }
    try {
      LocalDate localBookingDate = bookingDate.toLocalDate();
      LocalTime slotStart = LocalTime.parse(slotStartTime(bookingTime), TIME_FORMATTER);
      return Timestamp.valueOf(localBookingDate.atTime(slotStart)).getTime();
    } catch (RuntimeException e) {
      log.error(
          "event=slot_start_parse_failed booking_date={} booking_time={}",
          bookingDate,
          bookingTime,
          e);
      return null;
    }
  }
}
