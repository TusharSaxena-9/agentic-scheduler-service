package com.yesmadam.schedulerservice.jobs.autoJobDistribution.projections;

import java.sql.Date;
import java.sql.Timestamp;

/** Raw acceptance/status columns for one order, as returned by the batched writer read. */
public interface OrderAcceptanceProjection {

  Long getOrderId();

  /**
   * Whether an SP accepted through the distribution flow, as a number rather than a {@code
   * Boolean}.
   *
   * <p><b>The column's display width decides the JDBC type, and this one is not what you
   * expect.</b> Connector/J maps {@code tinyint(1)} to {@code Boolean}, but any other width —
   * including a plain {@code tinyint} — to {@code Byte}. {@code tbl_order.isDistributedJobAccepted}
   * is a plain {@code tinyint}, so declaring {@code Boolean} here throws {@code
   * UnsupportedOperationException: Cannot project java.lang.Byte to java.lang.Boolean} on the first
   * row.
   *
   * <p>Its neighbours {@code unassignWithRemoveReassigning} and {@code tbl_hub.is_franchise}
   * <em>are</em> {@code tinyint(1)} and map to {@code Boolean} correctly — which is why only this
   * one field differs, and why the inconsistency is easy to miss by reading the entity.
   *
   * <p>The query casts to {@code SIGNED} so the type is stated rather than inherited from a display
   * width somebody may change; the reader converts to {@code Boolean}. Null stays null — an order
   * that was never offered through distribution has no answer, which is not the same as "not
   * accepted".
   */
  Number getAccepted();

  Integer getSpId();

  Integer getIncentive();

  Integer getOrderStatusCode();

  Timestamp getOrderEndTimeWithDate();

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
}
