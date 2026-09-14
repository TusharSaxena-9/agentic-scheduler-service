package com.yesmadam.schedulerservice.jobs.spproductordersync.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.spproductordersync.repository.SpUnavailableProductSyncRepository;

/**
 * Each method is its own {@code @Transactional} unit rather than one transaction wrapping both —
 * the original DAO ran the two updates as two separate transactions too (each a plain Hibernate
 * {@code createSQLQuery(...).executeUpdate()} call), so this preserves the same non-atomic failure
 * boundary between them rather than silently making the pair atomic.
 */
@Service
public class SpProductOrderSyncServiceImpl implements SpProductOrderSyncService {

  private final SpUnavailableProductSyncRepository repository;

  public SpProductOrderSyncServiceImpl(SpUnavailableProductSyncRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public int incrementInventoryProductsPurchasedForDelivered() {
    return repository.incrementInventoryProductsPurchasedForDelivered();
  }

  @Override
  @Transactional
  public int syncDeliveredSpUnavailableProducts() {
    return repository.syncDeliveredSpUnavailableProducts();
  }
}
