package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.projections;

import java.sql.Timestamp;

/** Row shape returned by {@code FeedbackReminderOrderRepository.findCompletedOrdersPendingFeedback}. */
public interface PendingFeedbackOrderProjection {

  Long getOrderId();

  Long getMobile();

  /** Customer's display name, for the template's greeting. May be null/blank. */
  String getCustomerName();

  /** {@code tbl_order.order_start_time_with_date} — the "taken on" date shown in the reminder. */
  Timestamp getOrderStartTime();

  /** Comma-separated service names on the order, e.g. {@code "Facial, Manicure"}. Never null. */
  String getServiceNames();
}
