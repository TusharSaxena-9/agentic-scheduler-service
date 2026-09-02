package com.yesmadam.schedulerservice.jobs.autoassign.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the <b>next-day (D-1)</b> auto-assign cron — cron's {@code
 * AutoAssignComponent#findNonAssignJobAndAssignAServiceProviderToJobTomorrow}.
 *
 * <p>This is the only producer of D-1 distribution candidates. Without it the engine's SP-rejection
 * bypass, which exists solely for tomorrow's bookings, can never fire, and tomorrow's unassigned
 * orders sit untouched through the evening window that exists to cover them in advance.
 */
@WorkflowInterface
public interface AutoAssignTomorrowWorkflow {

  /** Executes one next-day auto-assign pass. */
  @WorkflowMethod
  void run();
}
