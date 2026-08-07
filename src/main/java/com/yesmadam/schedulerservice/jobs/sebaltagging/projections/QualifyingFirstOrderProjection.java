package com.yesmadam.schedulerservice.jobs.sebaltagging.projections;

import com.yesmadam.schedulerservice.jobs.sebaltagging.repository.SebalTaggingCohortRepository;

import java.time.LocalDate;

/** Maps {@link SebalTaggingCohortRepository#findQualifyingFirstOrders} rows by column alias. */
public interface QualifyingFirstOrderProjection {
  Long getOrderId();

  Long getUserId();

  Integer getSpId();

  LocalDate getBookingDate();

  /**
   * The order's dominant sub-category, resolved in the same query rather than by a follow-up
   * lookup — see {@link SebalTaggingCohortRepository#findQualifyingFirstOrders}. Never
   * {@code null}: an order with no resolvable sub-category doesn't survive the query's joins.
   */
  Integer getSubCategoryId();
}
