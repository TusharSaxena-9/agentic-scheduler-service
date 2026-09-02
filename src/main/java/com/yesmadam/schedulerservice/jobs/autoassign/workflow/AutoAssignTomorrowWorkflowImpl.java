package com.yesmadam.schedulerservice.jobs.autoassign.workflow;

import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;

import io.temporal.spring.boot.WorkflowImpl;

/**
 * Next-day (D-1) auto-assign. All orchestration is in {@link AbstractAutoAssignWorkflowImpl}; this
 * type exists to give the D-1 schedule its own workflow type and ID.
 *
 * <p>{@code NEXT_DAY} applies no lead-time floor — cron's DAO wraps that restriction in {@code if
 * (bookingDate == today)} — and does evaluate the SP-rejection bypass. See {@link AutoAssignScope}.
 *
 * <p>Its window (16:00-23:59) overlaps the D0 one (00:00-21:59), so both schedules can be mid-run
 * at once. A Schedule's default overlap policy is SKIP, which serialises a schedule against itself
 * but not against a different schedule, so the two runs genuinely contend for the distribution fire
 * gate — which is why that gate is a database-level named lock rather than an in-process flag.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class AutoAssignTomorrowWorkflowImpl extends AbstractAutoAssignWorkflowImpl
    implements AutoAssignTomorrowWorkflow {

  @Override
  protected AutoAssignScope scope() {
    return AutoAssignScope.NEXT_DAY;
  }

  @Override
  public void run() {
    runPass();
  }
}
