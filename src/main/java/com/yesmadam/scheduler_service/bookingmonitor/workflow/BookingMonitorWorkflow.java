package com.yesmadam.scheduler_service.bookingmonitor.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow interface for the booking monitor cron job. Runs every minute between 9AM and 11:59PM
 * IST. Alerts configured phone numbers if Android or iOS booking counts are zero in the last 2
 * minutes.
 */
@WorkflowInterface
public interface BookingMonitorWorkflow {

  @WorkflowMethod
  void run();
}
