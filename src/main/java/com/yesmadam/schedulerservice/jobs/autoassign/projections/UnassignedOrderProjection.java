package com.yesmadam.schedulerservice.jobs.autoassign.projections;

import java.sql.Date;
import java.sql.Timestamp;

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

  /**
   * {@code tbl_order.is_express_job}, or {@code null} when the query producing this row does not
   * select it — the per-chunk fresh-slot-time re-read query deliberately does not, since it exists
   * only to re-read start time and this flag never changes after creation.
   *
   * <p><b>Kept boxed, not primitive, on purpose.</b> A native query's row is backed by a JPA {@code
   * Tuple}; Spring Data's projection proxy catches the {@code IllegalArgumentException} {@code
   * Tuple.get} throws for a column the query never selected and returns {@code null} for a boxed
   * getter — but converting that {@code null} into a primitive {@code boolean} return type throws
   * {@code ConversionFailedException} instead. Do not "clean this up" to {@code boolean
   * isExpressJob()}; it turns the untouched re-read query into a landmine the first time anyone
   * calls the getter on one of its rows.
   */
  Boolean getIsExpressJob();

  /**
   * {@code tbl_order.created_at}, or {@code null} when the query producing this row does not select
   * it. See {@link #getIsExpressJob()} for why this stays boxed.
   */
  Timestamp getCreatedAt();
}
