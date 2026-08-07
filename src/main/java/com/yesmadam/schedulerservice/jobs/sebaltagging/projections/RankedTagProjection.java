package com.yesmadam.schedulerservice.jobs.sebaltagging.projections;

import java.math.BigDecimal;

/**
 * One row's freshly-computed rank/tier, plus enough of its own identity and current counts that
 * neither the batched apply step nor the summary-ledger insert needs to re-fetch anything.
 */
public interface RankedTagProjection {
  Long getId();

  Integer getSpId();

  Integer getSubCategoryId();

  Long getFirstJobUsersCount();

  Long getRebooked90dUsersCount();

  BigDecimal getRebookingRate();

  Integer getNationalRank();

  String getTier();
}
