package com.yesmadam.schedulerservice.jobs.spproductordersync.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for the SP delivered-unavailable-product sync run. Method names
 * mirror messagingapis' {@code SPDaoInterface} exactly, since these activities are a direct
 * migration of that DAO's two bulk-update queries.
 */
@ActivityInterface
public interface SpProductOrderSyncActivities {

  /**
   * Increments SP inventory counters for products delivered while still marked unavailable. Must
   * run before {@link #syncDeliveredSpUnavailableProducts()} — it reads the same rows that method
   * flips. Not idempotent under retry; see the workflow's activity-options javadoc.
   *
   * @return inventory rows updated
   */
  @ActivityMethod
  int incrementInventoryProductsPurchasedForDelivered();

  /**
   * Flips delivered-but-unavailable product rows to available/delivered.
   *
   * @return unavailable-product rows updated
   */
  @ActivityMethod
  int syncDeliveredSpUnavailableProducts();
}
