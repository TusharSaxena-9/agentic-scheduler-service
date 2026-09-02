package com.yesmadam.schedulerservice.jobs.autoassign.projections;

import java.sql.Date;

/** One unassigned order row as returned by the auto-assign candidate query. */
public interface UnassignedOrderProjection {

  Long getOrderId();

  /**
   * The order's booking day.
   *
   * <p><b>The query must {@code CAST(booking_date AS DATE)}.</b> The column is a {@code DATETIME},
   * so the driver hands back a {@code java.sql.Timestamp}, and Spring Data's projection proxy will
   * not narrow that to {@code java.sql.Date} — it throws {@code UnsupportedOperationException:
   * Cannot project java.sql.Timestamp to java.sql.Date} on the first row. That failure is invisible
   * to the compiler and to any test that mocks the repository, because a mock hands back whatever
   * type the test constructs. It only appears when the query really runs.
   */
  Date getBookingDate();

  /** Free-text slot label, e.g. {@code "02:00 PM - 03:00 PM"}. */
  String getBookingTime();
}
