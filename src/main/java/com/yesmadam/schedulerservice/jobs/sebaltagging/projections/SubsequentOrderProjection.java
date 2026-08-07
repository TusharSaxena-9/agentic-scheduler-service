package com.yesmadam.schedulerservice.jobs.sebaltagging.projections;

import com.yesmadam.schedulerservice.jobs.sebaltagging.repository.SebalTaggingCohortRepository;

import java.time.LocalDate;

/** Maps {@link SebalTaggingCohortRepository#findCompletedOrdersForRebookCheck} rows by column alias. */
public interface SubsequentOrderProjection {
  Long getUserId();

  Long getOrderId();

  LocalDate getBookingDate();
}
