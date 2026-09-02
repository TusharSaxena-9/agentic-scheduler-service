package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What one trigger batch actually did, returned so the numbers land in Temporal workflow history
 * rather than only in a log line.
 *
 * <p>The counts narrow at each step, which is what makes them worth keeping separately: {@code
 * received} orders arrive from the auto-assign gate, {@code eligible} survive the exclusion
 * filters, {@code enqueued} additionally pass the re-trigger guard and both eligibility checks,
 * {@code inserted} is how many of those were not already waiting, and {@code fired} is 0 or 1 —
 * never more, because a batch only ever opens the fire gate once.
 *
 * <p>A batch of 200 that reports {@code fired=0} is the normal case, not a failure: it means a
 * distribution was already live, so everything queued and waits its turn.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistributionTriggerResult {

  /** Orders handed over by the auto-assign gate. */
  private int received;

  /** Survivors of the category, franchise and single-hub exclusions. */
  private int eligible;

  /** Orders that passed the re-trigger guard, {@code isStillEligible} and the trigger rule. */
  private int enqueued;

  /**
   * Waiting rows actually created — lower than {@code enqueued} when an order was already queued.
   */
  private int inserted;

  /**
   * {@code 1} if this batch fired an order, {@code 0} if the gate was shut or nothing qualified.
   */
  private int fired;

  /** Nothing arrived — used for an empty batch so callers need not null-check. */
  public static DistributionTriggerResult empty() {
    return new DistributionTriggerResult(0, 0, 0, 0, 0);
  }
}
