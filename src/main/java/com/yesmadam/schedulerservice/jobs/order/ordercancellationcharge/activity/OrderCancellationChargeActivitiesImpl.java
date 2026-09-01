package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.activity;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.model.OrderCancellationChargeExpiryResult;
import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.service.OrderCancellationChargeService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/** Thin adapter between Temporal and {@link OrderCancellationChargeService}. */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class OrderCancellationChargeActivitiesImpl implements OrderCancellationChargeActivities {

  private final OrderCancellationChargeService service;

  public OrderCancellationChargeActivitiesImpl(OrderCancellationChargeService service) {
    this.service = service;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "expire-order-cancellation-charges")
  @HandleExceptions
  public OrderCancellationChargeExpiryResult expireOverdueCancellationCharges() {
    Timestamp cutoff = Timestamp.from(Instant.now());
    int updatedCount = service.expireOverdue(cutoff);
    return new OrderCancellationChargeExpiryResult(cutoff, updatedCount);
  }
}
