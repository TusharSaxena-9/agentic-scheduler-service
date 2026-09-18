package com.yesmadam.schedulerservice.jobs.paymentrefund.activity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundRowResult;
import com.yesmadam.schedulerservice.jobs.paymentrefund.service.PaymentRefundOrderCreationService;

import io.temporal.spring.boot.ActivityImpl;

/** Thin adapter between Temporal and {@link PaymentRefundOrderCreationService}. */
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class PaymentRefundActivitiesImpl implements PaymentRefundActivities {

  private static final Duration WINDOW_END_LAG = Duration.ofMinutes(10);
  private static final Duration WINDOW_START_LAG = Duration.ofMinutes(15);

  private final PaymentRefundOrderCreationService service;

  public PaymentRefundActivitiesImpl(PaymentRefundOrderCreationService service) {
    this.service = service;
  }

  @Override
  @Loggable(operation = "find-pending-payment-refund-checksums")
  @HandleExceptions
  public List<Long> findPendingChecksumIds() {
    Instant now = Instant.now();
    Timestamp windowStart = Timestamp.valueOf("2026-09-17 17:38:57"); // Timestamp.from(now.minus(WINDOW_START_LAG));
    Timestamp windowEnd = Timestamp.valueOf("2026-09-17 17:42:57");  //Timestamp.from(now.minus(WINDOW_END_LAG));
    return service.findPendingChecksumIds(windowStart, windowEnd);
  }

  @Override
  @Loggable(operation = "process-payment-refund-checksum-row")
  @HandleExceptions
  public PaymentRefundRowResult processChecksumRow(Long checksumId) {
    return service.processRow(checksumId);
  }
}
