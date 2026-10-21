package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The window a run covers: [17:00 the day before, 17:00 the day of) IST. Derived from the nominal
 * fire time and floored to 17:00, so late or retried runs cover the same day and days tile.
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

    // Coarse booking_date pre-filter for the index; it never decides correctness.
    LocalDate lower = start.toLocalDate().minusDays(1);
    LocalDate upperExclusive = end.toLocalDate().plusDays(1);
    return new BillingCheckWindow(
        Timestamp.from(start.toInstant()),
        Timestamp.from(end.toInstant()),
        Date.valueOf(lower),
        Date.valueOf(upperExclusive));
  }
}
