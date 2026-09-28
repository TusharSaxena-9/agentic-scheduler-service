package com.yesmadam.schedulerservice.jobs.ozuzuorder.activity;

import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderCodesBatch;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderSyncResult;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.service.OzuzuOrderSyncService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter between Temporal and {@link OzuzuOrderSyncService} — all logic lives in the
 * service so it stays testable with plain JUnit (rule 12).
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class OzuzuOrderSyncActivitiesImpl implements OzuzuOrderSyncActivities {

  private final OzuzuOrderSyncService ozuzuOrderSyncService;

  public OzuzuOrderSyncActivitiesImpl(OzuzuOrderSyncService ozuzuOrderSyncService) {
    this.ozuzuOrderSyncService = ozuzuOrderSyncService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "fetch-ozuzu-order-codes")
  @HandleExceptions
  public OzuzuOrderCodesBatch fetchPendingOrderCodes() {
    return ozuzuOrderSyncService.fetchPendingOrderCodes();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Heartbeats once per code visited (rule 10) — this can run long enough to exceed a minute
   * when the window holds many orders, and a heartbeat is how a cancellation is delivered and how
   * a stuck run is told apart from a merely slow one.
   */
  @Override
  @Loggable(operation = "create-ozuzu-orders")
  @HandleExceptions
  public OzuzuOrderSyncResult createOrders(List<String> orderCodes) {
    return ozuzuOrderSyncService.createOrders(
        orderCodes, processed -> Activity.getExecutionContext().heartbeat(processed));
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "update-ozuzu-orders")
  @HandleExceptions
  public OzuzuOrderSyncResult updateOrders(List<String> orderCodes) {
    return ozuzuOrderSyncService.updateOrders(
        orderCodes, processed -> Activity.getExecutionContext().heartbeat(processed));
  }
}
