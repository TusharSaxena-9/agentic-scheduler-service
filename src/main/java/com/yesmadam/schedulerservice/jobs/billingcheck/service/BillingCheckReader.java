package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.billingcheck.projections.BillingCheckOrderProjection;
import com.yesmadam.schedulerservice.jobs.billingcheck.repository.BillingCheckRepository;

/** The job's only database access, and it is a pure read — replica, short transaction (rule 13). */
@Service
public class BillingCheckReader {

  private final BillingCheckRepository repository;

  public BillingCheckReader(BillingCheckRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<BillingCheckOrderProjection> findEligibleOrders(
      List<Integer> statusCodes,
      List<Integer> categoryIds,
      BillingCheckWindow window,
      long afterOrderId,
      int limit) {
    return repository.findEligibleOrders(
        statusCodes,
        categoryIds,
        window.bookingDateLower(),
        window.bookingDateUpper(),
        window.start(),
        window.end(),
        afterOrderId,
        limit);
  }
}
