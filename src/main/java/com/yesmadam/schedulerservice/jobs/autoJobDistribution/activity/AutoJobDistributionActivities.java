package com.yesmadam.schedulerservice.jobs.autoJobDistribution.activity;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPassResult;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity for the Auto Job Distribution advance loop.
 *
 * <p>Deliberately a single activity, unlike the three-activity split in {@code spNotReachedHome}.
 * The pass is not three independent steps: closing a round and then giving the waiting pool its
 * chance are causally linked — the fire is only correct <em>because</em> the close already
 * committed. Two activities could interleave with another pass between them and fire while a round
 * was still live.
 */
@ActivityInterface
public interface AutoJobDistributionActivities {

  /**
   * Runs one advance pass: evaluate live rounds, close or advance them, then fire at most one fresh
   * distribution from the waiting pool.
   *
   * @return what the pass did
   */
  @ActivityMethod
  DistributionPassResult processActiveDistributions();
}
