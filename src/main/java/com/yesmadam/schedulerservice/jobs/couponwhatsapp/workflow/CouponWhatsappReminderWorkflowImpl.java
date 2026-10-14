package com.yesmadam.schedulerservice.jobs.couponwhatsapp.workflow;

import java.time.Duration;

import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.activity.CouponWhatsappReminderActivities;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * Coupon-WhatsApp reminder enqueue workflow: a single retryable activity call that computes
 * yesterday's window, fetches every still-pending claim, and publishes to SQS. Unlike
 * wallet-expiry (which chunks per day-offset over a much larger table), this covers the whole
 * day's candidates in one call — expected daily volume (claimed brand coupons) is small.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class CouponWhatsappReminderWorkflowImpl implements CouponWhatsappReminderWorkflow {

  private static final Logger log = Workflow.getLogger(CouponWhatsappReminderWorkflowImpl.class);

  /** Timeouts are placeholders, sized generously for a single-call, unchunked, low-volume job. */
  private final CouponWhatsappReminderActivities activities =
      Workflow.newActivityStub(
          CouponWhatsappReminderActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              .setScheduleToCloseTimeout(Duration.ofMinutes(40))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  @Loggable(operation = "coupon-whatsapp-reminder-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    int published = activities.enqueuePendingCouponWhatsapp();

    log.info("event=workflow_completed workflow_id={} published={}", workflowId, published);
  }
}
