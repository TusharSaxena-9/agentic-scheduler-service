package com.yesmadam.schedulerservice.jobs.billingcheck.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckChunk;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckSendResult;
import com.yesmadam.schedulerservice.jobs.billingcheck.service.BillingCheckService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;

/**
 * Thin adapter between Temporal and {@link BillingCheckService}. Stays on {@code standard} with the
 * workflow rather than routing the send to {@code notification}: the queue name is only a Spring
 * placeholder, which workflow code cannot resolve (CLAUDE.md rule 1) — see {@code
 * FeedbackReminderActivitiesImpl} for the same trade-off.
 */
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class BillingCheckActivitiesImpl implements BillingCheckActivities {

  private final BillingCheckService service;

  public BillingCheckActivitiesImpl(BillingCheckService service) {
    this.service = service;
  }

  @Override
  @Loggable(operation = "billing-check-fetch-chunk")
  @HandleExceptions
  public BillingCheckChunk fetchChunk(long nominalFireTimeMillis, long afterOrderId) {
    return service.fetchChunk(nominalFireTimeMillis, afterOrderId);
  }

  @Override
  @Loggable(operation = "billing-check-send-chunk")
  @HandleExceptions
  public BillingCheckSendResult sendChunk(BillingCheckChunk chunk) {
    return service.sendChunk(chunk, completed -> Activity.getExecutionContext().heartbeat(completed));
  }
}
