package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

import java.sql.Date;
import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * The per-order facts the advance loop needs to decide a live round's fate, read in one batched
 * query against the <b>writer</b>.
 *
 * <p>Writer, not replica, on purpose: admin commits the acceptance moments before this runs, so a
 * lagging replica would make the loop escalate a round that has already been accepted — notifying
 * more SPs for a job somebody already took.
 */
@Data
@AllArgsConstructor
public class OrderAcceptanceView {

  /** True when an SP accepted through the distribution flow. */
  private Boolean accepted;

  /** The assigned SP, whether or not the assignment came from distribution. */
  private Integer spId;

  private Integer incentive;

  private Integer orderStatusCode;

  /**
   * End of the order's booked slot — the guard against escalating an order whose time has passed.
   */
  private Timestamp orderEndTimeWithDate;

  private Date bookingDate;
}
