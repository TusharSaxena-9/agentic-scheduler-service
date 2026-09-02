package com.yesmadam.schedulerservice.jobs.producthublivestock.projections;

/** One hub/product combination whose {@code live_stock_new} ("Uni Live Stock") is at or below zero. */
public interface LiveStockAlertProjection {

  Integer getHubId();

  String getHubName();

  Integer getProductId();

  String getProductName();

  Float getLiveStock();
}
