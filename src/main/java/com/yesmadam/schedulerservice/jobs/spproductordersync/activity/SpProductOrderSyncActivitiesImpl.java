package com.yesmadam.schedulerservice.jobs.spproductordersync.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.spproductordersync.service.SpProductOrderSyncService;

import io.temporal.spring.boot.ActivityImpl;

/**
 * Implementation of SP product-order-sync activities. Thin adapter between Temporal and the
 * service layer — all business logic lives in {@link SpProductOrderSyncService}, which imports
 * nothing from {@code io.temporal} and is unit-tested directly (rule 12).
 */
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class SpProductOrderSyncActivitiesImpl implements SpProductOrderSyncActivities {

  private final SpProductOrderSyncService spProductOrderSyncService;

  public SpProductOrderSyncActivitiesImpl(SpProductOrderSyncService spProductOrderSyncService) {
    this.spProductOrderSyncService = spProductOrderSyncService;
  }

  @Override
  @Loggable(operation = "increment-inventory-products-purchased-for-delivered")
  @HandleExceptions
  public int incrementInventoryProductsPurchasedForDelivered() {
    return spProductOrderSyncService.incrementInventoryProductsPurchasedForDelivered();
  }

  @Override
  @Loggable(operation = "sync-delivered-sp-unavailable-products")
  @HandleExceptions
  public int syncDeliveredSpUnavailableProducts() {
    return spProductOrderSyncService.syncDeliveredSpUnavailableProducts();
  }
}
