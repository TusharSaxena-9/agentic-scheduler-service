package com.yesmadam.schedulerservice.jobs.cinntrapassbook.workflow;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.activity.CinntraPassbookSyncActivities;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;

import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Posts yesterday's SP commission (reason 11) and convenience fee (reason 102) to SAP as AR
 * invoices, or drafts for Notion SPs. Commission runs first, then convenience, each independent of
 * the other as in tracking.
 *
 * <p>Heavy tier, like every SAP job. Shape and activity sizing are in {@link
 * CinntraPassbookSyncRunner}. A chunk that fails after its retries does not stop the rest; the run
 * fails at the end so the failure is visible. A single entry SAP rejects is not a run failure — it
 * is counted, logged to {@code tbl_sap_error_log}, and left unprocessed, as in tracking.
 *
 * <p>Pure orchestration — all side effects go through activities. Do not inject Spring beans or use
 * {@code @Value} here; workflow code must stay deterministic for Temporal replay.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.heavy}")
public class CinntraPassbookCommissionConvenienceSyncWorkflowImpl
    implements CinntraPassbookCommissionConvenienceSyncWorkflow {

  private static final Logger log =
      Workflow.getLogger(CinntraPassbookCommissionConvenienceSyncWorkflowImpl.class);

  private final CinntraPassbookSyncActivities activities =
      CinntraPassbookSyncRunner.newActivityStub();

  /** {@inheritDoc} */
  @Override
  public void run() {
    // Stable across replay. Only its IST date matters: the selection posts the day before it, and
    // the
    // schedule's jitter and catch-up window are both far too small to move it across midnight.
    long nominalFireTimeMillis = Workflow.currentTimeMillis();
    log.info(
        "event=workflow_started workflow_id={} nominal_fire_time_millis={}",
        Workflow.getInfo().getWorkflowId(),
        nominalFireTimeMillis);

    List<String> failures = new ArrayList<>();
    for (CinntraPassbookSyncKind kind :
        List.of(CinntraPassbookSyncKind.COMMISSION, CinntraPassbookSyncKind.CONVENIENCE)) {
      CinntraPassbookSyncRunner.Outcome outcome =
          CinntraPassbookSyncRunner.run(activities, kind, nominalFireTimeMillis);
      if (outcome.activityFailed()) {
        failures.add(outcome.describe());
      }
    }

    if (!failures.isEmpty()) {
      throw ApplicationFailure.newNonRetryableFailure(
          String.join("; ", failures), "CinntraPassbookCommissionConvenienceFailed");
    }
  }
}
