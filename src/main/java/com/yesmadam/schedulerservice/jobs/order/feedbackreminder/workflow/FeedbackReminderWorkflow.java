package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Every 5 minutes, WhatsApp-nudges customers whose completed booking still has no rating at the
 * 3h/24h/48h checkpoints.
 */
@WorkflowInterface
public interface FeedbackReminderWorkflow {

  @WorkflowMethod
  void run();
}
