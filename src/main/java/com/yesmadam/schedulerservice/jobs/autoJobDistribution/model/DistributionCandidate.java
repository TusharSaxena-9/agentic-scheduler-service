package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

import java.sql.Date;
import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * The order facts needed to decide whether a queued order may be fired, and how it ranks against
 * the other candidates.
 *
 * <p>A job-local projection rather than the shared {@code Order} entity: only these columns are
 * read, and binding to the full entity would couple this job to every unrelated change in {@code
 * tbl_order}.
 *
 * <p>The last three are read for the <b>trigger</b> side only — the exclusion filters that decide
 * whether an order may enter the waiting pool at all. The advance loop ignores them, because an
 * order that failed an exclusion never reached the queue in the first place.
 */
@Data
@AllArgsConstructor
public class DistributionCandidate {

  private Long orderId;

  /** Non-null means somebody is already assigned — the order is no longer distributable. */
  private Integer spAssignByUser;

  private Integer orderStatusCode;

  private Date bookingDate;

  /** Slot string, e.g. {@code "02:00 PM - 03:00 PM"}; parsed for the priority sort. */
  private String bookingTime;

  private Timestamp orderEndTimeWithDate;

  /** True when ops explicitly pulled the order out of the auto-reassign pool. */
  private Boolean unassignWithRemoveReassigning;

  /** Checked against {@code EXCLUDED_CATEGORY_IDS}; a null category is never excluded. */
  private Integer categoryId;

  /** Checked against {@code RESTRICTED_HUB_ID} while the single-hub gateway is on. */
  private Integer hubId;

  /**
   * {@code ysmdm_admin.tbl_hub.is_franchise}, resolved by an outer join rather than a per-order
   * lookup. Null means the order has no hub, or the hub row is missing — neither is a franchise,
   * matching the original's {@code hub != null && TRUE.equals(hub.getIs_franchise())}.
   */
  private Boolean hubIsFranchise;
}
