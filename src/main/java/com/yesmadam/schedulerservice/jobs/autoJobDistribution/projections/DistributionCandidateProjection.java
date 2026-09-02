package com.yesmadam.schedulerservice.jobs.autoJobDistribution.projections;

import java.sql.Date;
import java.sql.Timestamp;

/** Raw candidate columns for one queued order, as returned by the batched read. */
public interface DistributionCandidateProjection {

  Long getOrderId();

  Integer getSpAssignByUser();

  Integer getOrderStatusCode();

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

  String getBookingTime();

  Timestamp getOrderEndTimeWithDate();

  /**
   * Whether ops pulled the order out of the auto-reassign pool.
   *
   * <p>{@code Number}, not {@code Boolean}, for the reason spelled out on {@code
   * OrderAcceptanceProjection#getAccepted()}: Connector/J decides between {@code Boolean} and
   * {@code Byte} from the column's <em>display width</em>, so a {@code tinyint(1)} that someone
   * later widens to {@code tinyint} would start throwing at runtime with no code change. The query
   * casts and the reader converts, which removes the dependency entirely.
   */
  Number getUnassignWithRemoveReassigning();

  Integer getCategoryId();

  Integer getHubId();

  /**
   * {@code ysmdm_admin.tbl_hub.is_franchise}; null when the order has no hub row. See above for why
   * this is a {@code Number}.
   */
  Number getHubIsFranchise();
}
