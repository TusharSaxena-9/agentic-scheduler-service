package com.yesmadam.schedulerservice.jobs.spproductordersync.service;

/**
 * Business logic for the SP delivered-unavailable-product sync run — a direct migration of
 * messagingapis' {@code SPDaoInterface#incrementInventoryProductsPurchasedForDelivered} and
 * {@code SPDaoInterface#syncDeliveredSpUnavailableProducts}. Imports nothing from {@code
 * io.temporal} (rule 12), so it is tested with plain JUnit.
 */
public interface SpProductOrderSyncService {

  /**
   * Increments SP inventory counters for products delivered while still marked unavailable.
   *
   * @return inventory rows updated
   */
  int incrementInventoryProductsPurchasedForDelivered();

  /**
   * Flips delivered-but-unavailable product rows to available/delivered.
   *
   * @return unavailable-product rows updated
   */
  int syncDeliveredSpUnavailableProducts();
}
