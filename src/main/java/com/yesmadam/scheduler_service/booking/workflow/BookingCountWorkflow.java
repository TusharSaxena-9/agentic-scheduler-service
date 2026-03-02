package com.yesmadam.scheduler_service.booking.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface BookingCountWorkflow {
  /**
   * Executes the booking count workflow. This method orchestrates the fetching and saving of
   * booking counts.
   */
  @WorkflowMethod
  void run();
}
