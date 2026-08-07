package com.yesmadam.schedulerservice.jobs.roseorder.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Eligibility thresholds resolved from {@code tbl_settings} once per run and passed to every chunk
 * activity, so all chunks of a run apply identical gates even if settings change mid-run.
 *
 * <p>Serialized into Temporal workflow history — keep Jackson-friendly (no-args constructor,
 * getters/setters).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoseOrderEligibilityThresholds {

  /** Minimum assign-to-complete ratio over the last 30 days (percentage). */
  private BigDecimal assignToCompleteMinPct;

  /** Minimum average rating over the most-recent rated jobs. */
  private BigDecimal ratingMin;

  /** Minimum all-time completed orders. */
  private int minCompletedOrders;
}
