package com.yesmadam.schedulerservice.jobs.cinntrapassbook.workflow;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.activity.CinntraPassbookSyncActivities;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;

import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Posts the last 30 minutes of {@code tbl_sp_passbook} to SAP as journal entries, journal vouchers
 * and vendor payments, and retries anything from the 90 minutes before that is still unprocessed.
 *
 * <p>Standard tier: the work is SAP calls, not long database holds. Shape and activity sizing are
 * in {@link CinntraPassbookSyncRunner}. A chunk that fails after its retries does not stop the
 * rest; the run fails at the end so the failure is visible. A single entry SAP rejects is not a
 * run failure — it is counted, logged to {@code tbl_sap_error_log}, and left unprocessed, as in
 * tracking.
 *
 * <p>Pure orchestration — all side effects go through activities. Do not inject Spring beans or use
 * {@code @Value} here; workflow code must stay deterministic for Temporal replay.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class CinntraPassbookJournalSyncWorkflowImpl implements CinntraPassbookJournalSyncWorkflow {

  private static final Logger log =
      Workflow.getLogger(CinntraPassbookJournalSyncWorkflowImpl.class);

  private final CinntraPassbookSyncActivities activities =
      CinntraPassbookSyncRunner.newActivityStub();

  /** {@inheritDoc} */
  @Override
  public void run() {
    // Stable across replay. The schedule pins jitter off, so this is the scheduled time give or
    // take
    // the start delay, and the selection rounds it to its slot.
    long nominalFireTimeMillis = Workflow.currentTimeMillis();
    log.info(
        "event=workflow_started workflow_id={} nominal_fire_time_millis={}",
        Workflow.getInfo().getWorkflowId(),
        nominalFireTimeMillis);

    CinntraPassbookSyncRunner.Outcome outcome =
        CinntraPassbookSyncRunner.run(
            activities, CinntraPassbookSyncKind.JOURNAL, nominalFireTimeMillis);

    if (outcome.activityFailed()) {
      throw ApplicationFailure.newNonRetryableFailure(
          outcome.describe(), "CinntraPassbookJournalChunkFailed");
    }
  }
}
