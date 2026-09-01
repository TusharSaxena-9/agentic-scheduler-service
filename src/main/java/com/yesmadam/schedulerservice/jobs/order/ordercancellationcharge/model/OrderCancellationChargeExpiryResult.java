package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.model;

import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bounded payload returned across the activity boundary: a cutoff and a count, never the rows
 * themselves (CLAUDE.md rule 9).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancellationChargeExpiryResult {

  /** The "now" the activity computed and filtered {@code expiry_at} against. */
  private Timestamp cutoff;

  /** Number of rows flipped from CREATED/CHARGED to EXPIRED. */
  private int updatedCount;
}
