package com.yesmadam.schedulerservice.jobs.autoassign.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow contract for the <b>same-day (D0)</b> auto-assign cron — cron's {@code
 * AutoAssignComponent#findNonAssignJobAndAssignAServiceProviderToJob}.
 *
 * <p>Sibling of {@link AutoAssignTomorrowWorkflow}; both share {@link
 * AbstractAutoAssignWorkflowImpl} and differ only by scope.
 */
@WorkflowInterface
public interface AutoAssignWorkflow {

  /** Executes one same-day auto-assign pass. */
  @WorkflowMethod
  void run();
}
