package com.yesmadam.schedulerservice.jobs.sebaltagging.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of the final, run-wide re-tiering pass over the whole aggregate table. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SebalTaggingTierResult {

  /** Distinct sub-categories re-ranked this run. */
  private int subCategoriesTiered;

  /** SPs with a non-zero sample that received/kept a tier this run. */
  private int spsTagged;
}
