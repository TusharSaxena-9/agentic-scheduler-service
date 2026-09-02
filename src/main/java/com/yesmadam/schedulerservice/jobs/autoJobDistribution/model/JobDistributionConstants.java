package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Static configuration for Auto Job Distribution, ported from cron's {@code
 * JobDistributionConstants} (SAAB-349). Only the constants the advance loop actually reads are
 * carried over; the trigger-side ones stay in cron until that half migrates.
 *
 * <p>The incentive ladder and the round caps are business rules that decide real payouts — do not
 * adjust them here to fix a test.
 */
public final class JobDistributionConstants {

  private JobDistributionConstants() {}

  /** Acceptance window per round, in minutes. Matches the 5-minute schedule cadence. */
  public static final int ROUND_WINDOW_MINUTES = 5;

  /**
   * Give-up threshold in <b>consecutive most-recent</b> rounds that notified zero SPs at an
   * unchanged incentive. A round that notifies at least one SP resets the streak, so this caps dry
   * attempts in a row, not total rounds for an order.
   */
  public static final int MAX_CONSECUTIVE_ZERO_ROUNDS = 3;

  /** Round-1 incentive, rupees. */
  public static final int BASE_INCENTIVE = 100;

  /** Added per escalating round, but only when the prior round actually reached SPs. */
  public static final int INCENTIVE_STEP = 100;

  /** Ceiling of the ladder — reached at round 3 when every round notified SPs. */
  public static final int MAX_INCENTIVE = 300;

  /** Reassign attempts at or above which an unassigned order escalates to distribution. */
  public static final int REASSIGN_THRESHOLD = 2;

  /**
   * Below this many minutes to start, an order can no longer be picked for a fresh round — there is
   * not enough runway for shortlist, notify, accept and travel. Orders in the 45-60 minute band are
   * still eligible and naturally sort first.
   */
  public static final int MIN_MINUTES_BEFORE_START = 45;

  /**
   * Categories that never enter Auto Job Distribution — male hydra, slimming, laser. Trigger-side
   * only: an excluded order is dropped before it can reach the waiting pool, so the advance loop
   * never sees one.
   */
  public static final Set<Integer> EXCLUDED_CATEGORY_IDS =
      new HashSet<>(Arrays.asList(83, 84, 87, 88, 89));

  /**
   * Production test gateway. While {@code true}, only {@link #RESTRICTED_HUB_ID}'s orders are
   * distributed and every other hub's fall through exactly as an excluded-category or franchise
   * order already does — no distribution, left to normal auto-assign or manual handling.
   */
  public static final boolean RESTRICT_TO_SINGLE_HUB = false;

  /** The one hub distribution is confined to while {@link #RESTRICT_TO_SINGLE_HUB} is true. */
  public static final Integer RESTRICTED_HUB_ID = 127; // Sikkim

  /** Orders per HTTP chunk when firing to admin. */
  public static final int BATCH_CHUNK_SIZE = 200;

  /**
   * Doubles as the priority-sort pivot and the organic trigger window, deliberately one constant so
   * the two rules cannot drift apart. Below it, time-to-start is the primary sort key; at or above
   * it, reassign count is.
   */
  public static final int PRIORITY_TIME_THRESHOLD_MINUTES = 60;

  /**
   * D-1 only: an SP rejecting a tomorrow-booking order after this hour bypasses the reassign
   * threshold.
   */
  public static final int D1_SP_REJECTION_BYPASS_CUTOFF_HOUR = 15;

  /**
   * All five cancel codes (13-17), matching empapi's authoritative {@code OrderStatus}. Cron's own
   * enum originally defined only 13-16 and silently missed 17, which let a cancelled order advance
   * to round 2 — confirmed live on UAT order 5154874. Keep all five.
   */
  public static final Set<Integer> CANCELLED_ORDER_STATUS_CODES =
      new HashSet<>(Arrays.asList(13, 14, 15, 16, 17));

  /**
   * The only two statuses this feature ever distributes: NEW_REQUEST and
   * ASSIGN_TO_SERVICE_PROVIDER.
   */
  public static final int ORDER_NEW_REQUEST = 1;

  public static final int ORDER_ASSIGN_TO_SERVICE_PROVIDER = 3;

  /** {@code log_by} stamped on the OrderHistory audit rows this job writes. */
  public static final long SYSTEM_LOG_BY = 11L;

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  /**
   * {@code Locale.ENGLISH} is pinned deliberately: the {@code a} (AM/PM) pattern is locale
   * sensitive, and on a JVM with a non-English default locale these parses fail — which would make
   * every order's start time unknown and push it to the back of the priority queue. Same pin as
   * cron's {@code SlotsUtilities}.
   */
  private static final DateTimeFormatter SLOT_TIME =
      DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

  private static final DateTimeFormatter SLOT_TIME_HOUR_ONLY =
      DateTimeFormatter.ofPattern("hh a", Locale.ENGLISH);

  private static final DateTimeFormatter SLOT_TIME_HOUR_ONLY_SHORT =
      DateTimeFormatter.ofPattern("h a", Locale.ENGLISH);

  private static final String FORMAT_HH_MM_A = "(((0[1-9])|(1[0-2])):([0-5])(0|3)\\s(A|P)M)";
  private static final String FORMAT_HH_A = "(((0[1-9])|(1[0-2]))\\s(A|P)M)";
  private static final String FORMAT_H_A = "(([1-9])\\s(A|P)M)";

  /** Today's D-1 rejection cutoff, in IST. Recomputed per call, never cached. */
  public static Timestamp todaysD1RejectionBypassCutoff() {
    return Timestamp.valueOf(LocalDate.now(IST).atTime(D1_SP_REJECTION_BYPASS_CUTOFF_HOUR, 0));
  }

  /** True when {@code bookingDate} is tomorrow in IST — the D-1 scope check for the bypass. */
  public static boolean isTomorrowBooking(java.sql.Date bookingDate) {
    return bookingDate != null && bookingDate.toLocalDate().isEqual(LocalDate.now(IST).plusDays(1));
  }

  /**
   * Normalises the several slot-string shapes in {@code tbl_order.booking_time} to {@code hh:mm a}.
   * Takes the start half of a {@code "02:00 PM - 03:00 PM"} range.
   */
  public static String normaliseSlotStart(String bookingSlot) {
    int dash = bookingSlot.indexOf('-');
    String start =
        (dash >= 0 ? bookingSlot.substring(0, dash) : bookingSlot)
            .trim()
            .toUpperCase(Locale.ENGLISH);
    if (start.matches(FORMAT_HH_MM_A)) {
      return start;
    }
    if (start.matches(FORMAT_HH_A)) {
      return LocalTime.parse(start, SLOT_TIME_HOUR_ONLY).format(SLOT_TIME);
    }
    if (start.matches(FORMAT_H_A)) {
      return LocalTime.parse(start, SLOT_TIME_HOUR_ONLY_SHORT).format(SLOT_TIME);
    }
    return start;
  }

  /**
   * Epoch millis of the order's booked start ({@code booking_date} + parsed {@code booking_time}),
   * or {@code null} when it cannot be determined. Single source of truth for {@link
   * #minutesUntilStart}.
   */
  public static Long startTimeMillis(java.sql.Date bookingDate, String bookingTime) {
    if (bookingDate == null || bookingTime == null || bookingTime.trim().isEmpty()) {
      return null;
    }
    try {
      LocalTime start = LocalTime.parse(normaliseSlotStart(bookingTime), SLOT_TIME);
      return Timestamp.valueOf(bookingDate.toLocalDate().atTime(start)).getTime();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /**
   * Minutes from now until the booked start; negative once past.
   *
   * @return {@link Long#MAX_VALUE} when the start time cannot be determined — never throws, and
   *     always sorts last, matching the original's parse-failure-is-safe behaviour
   */
  public static long minutesUntilStart(java.sql.Date bookingDate, String bookingTime) {
    Long startMillis = startTimeMillis(bookingDate, bookingTime);
    if (startMillis == null) {
      return Long.MAX_VALUE;
    }
    return (startMillis - System.currentTimeMillis()) / 60_000L;
  }
}
