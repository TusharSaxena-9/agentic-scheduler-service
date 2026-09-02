package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What one advance pass did. Returned across the activity boundary so the workflow can log it and
 * record it against the cron-timing row, so Temporal needs a no-arg constructor and bean accessors.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistributionPassResult {

  /** Live rounds found at the start of the pass. */
  private int activeRounds;

  /** Rounds closed terminally this pass. */
  private int closedRounds;

  /** Rounds handed to admin to advance to the next incentive tier. */
  private int advancedRounds;

  /** Whether a fresh Round 1 was fired from the waiting pool. */
  private boolean firedNewDistribution;
}
