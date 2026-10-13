package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The 24-hour window a run covers: {@code [17:00 the day before, 17:00 the day of)} IST.
 *
 * <p>Derived from the run's nominal fire time and <b>floored to 17:00</b>, so the window is the same
 * whether the run starts at 17:00:20 with jitter, an hour late, or the next morning on catch-up, and
 * consecutive days tile with no gap and no overlap (CLAUDE.md rule 1).
 */
record BillingCheckWindow(Timestamp start, Timestamp end, Date bookingDateLower, Date bookingDateUpper) {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  static final LocalTime CUTOFF = LocalTime.of(17, 0);

  static BillingCheckWindow forNominalFireTime(long nominalFireTimeMillis) {
    ZonedDateTime nominal = Instant.ofEpochMilli(nominalFireTimeMillis).atZone(IST);
    ZonedDateTime end = nominal.toLocalDate().atTime(CUTOFF).atZone(IST);
    if (nominal.isBefore(end)) {
      end = end.minusDays(1); // fired (or replayed) before today's cutoff: cover the previous day
    }
    ZonedDateTime start = end.minusDays(1);

    // Coarse booking_date pre-filter so the query can use the status/booking_date index rather than
    // scan every completed order (same reasoning as FeedbackReminderService). -1 day covers a
    // booking that started the day before its slot ended; it never decides correctness.
    LocalDate lower = start.toLocalDate().minusDays(1);
    LocalDate upperExclusive = end.toLocalDate().plusDays(1);
    return new BillingCheckWindow(
        Timestamp.from(start.toInstant()),
        Timestamp.from(end.toInstant()),
        Date.valueOf(lower),
        Date.valueOf(upperExclusive));
  }
}
