package com.yesmadam.schedulerservice.jobs.autoassign.workflow;

import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;

import io.temporal.spring.boot.WorkflowImpl;

/**
 * Same-day (D0) auto-assign. All orchestration is in {@link AbstractAutoAssignWorkflowImpl}; this
 * type exists to give the D0 schedule its own workflow type and ID.
 *
 * <p>{@code SAME_DAY} applies the lead-time floor and does not evaluate the D-1 SP-rejection bypass
 * — see {@link AutoAssignScope}.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class AutoAssignWorkflowImpl extends AbstractAutoAssignWorkflowImpl
    implements AutoAssignWorkflow {

  @Override
  protected AutoAssignScope scope() {
    return AutoAssignScope.SAME_DAY;
  }

  @Override
  public void run() {
    runPass();
  }
}
