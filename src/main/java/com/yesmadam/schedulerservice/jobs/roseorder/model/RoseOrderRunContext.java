package com.yesmadam.schedulerservice.jobs.roseorder.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the workflow needs to drive one eligibility run: the SP ids to evaluate and the
 * thresholds to apply. Fetched by a single activity so the values are recorded in workflow history
 * and stay stable across retries and replays.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoseOrderRunContext {

  /** Ids of all active service providers to evaluate. */
  private List<Integer> spIds;

  /** Eligibility gates resolved from settings for this run. */
  private RoseOrderEligibilityThresholds thresholds;
}
