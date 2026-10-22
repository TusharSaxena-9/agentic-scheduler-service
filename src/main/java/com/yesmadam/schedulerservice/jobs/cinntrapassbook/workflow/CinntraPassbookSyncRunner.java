package com.yesmadam.schedulerservice.jobs.cinntrapassbook.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.activity.CinntraPassbookSyncActivities;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookChunkResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSelection;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;

/**
 * The orchestration the three passbook workflows share: select, then walk the ids in chunks of
 * {@link #CHUNK_SIZE}, one retryable activity per chunk. A chunk that still fails after its retries
 * is logged and the remaining chunks still run — tracking also kept going past a failed entry — and
 * the caller fails the run afterwards.
 *
 * <p>Workflow code: deterministic, no I/O, logging through {@link Workflow#getLogger}.
 */
final class CinntraPassbookSyncRunner {

  private static final Logger log = Workflow.getLogger(CinntraPassbookSyncRunner.class);

  /**
   * Entries per chunk activity. Each is one SAP call, two or three with the address retry, so a
   * chunk is well under a minute when SAP is healthy and a retry repeats little.
   */
  static final int CHUNK_SIZE = 20;

  private CinntraPassbookSyncRunner() {}

  /**
   * One stub, two profiles (CLAUDE.md rule 4, "per-activity options on a single stub"). Placeholder
   * sizing until one real run has been observed.
   *
   * <p>Default (selection): one or two id-only range reads over a bounded window — the "query over
   * a bounded set" row.
   *
   * <p>{@code CinntraPassbookSyncChunk}: the "one chunk of a chunked job" row. The service
   * heartbeats after every entry, and one entry is at most two posts and a PATCH, each with a 10s
   * connect and 60s read timeout, so 5 minutes of silence means it is stuck. Three attempts are
   * safe because a retried chunk skips what an earlier attempt posted. scheduleToClose covers 3 x
   * 30m plus backoff.
   */
  static CinntraPassbookSyncActivities newActivityStub() {
    return Workflow.newActivityStub(
        CinntraPassbookSyncActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setScheduleToCloseTimeout(Duration.ofMinutes(20))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
        Map.of(
            "CinntraPassbookSyncChunk",
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(30))
                .setHeartbeatTimeout(Duration.ofMinutes(5))
                .setScheduleToCloseTimeout(Duration.ofHours(2))
                .build()));
  }

  /**
   * Runs one kind end to end. Never throws on an activity failure; the outcome says what failed.
   */
  static Outcome run(
      CinntraPassbookSyncActivities activities,
      CinntraPassbookSyncKind kind,
      long nominalFireTimeMillis) {
    String workflowId = Workflow.getInfo().getWorkflowId();
    Outcome outcome = new Outcome(kind);

    CinntraPassbookSelection selection;
    try {
      selection = activities.selectPassbookEntries(kind, nominalFireTimeMillis);
    } catch (ActivityFailure ex) {
      outcome.selectionFailed = true;
      log.error(
          "event=cinntra_passbook_selection_failed workflow_id={} kind={} error={}",
          workflowId,
          kind,
          ex.getMessage());
      return outcome;
    }

    List<Long> ids =
        selection == null || selection.getPassbookIds() == null
            ? List.of()
            : selection.getPassbookIds();
    outcome.entries = ids.size();
    log.info(
        "event=cinntra_passbook_selected workflow_id={} kind={}"
            + " entries={} window_start={} window_end={}",
        workflowId,
        kind,
        ids.size(),
        selection == null ? null : selection.getWindowStart(),
        selection == null ? null : selection.getWindowEnd());

    for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
      List<Long> chunk = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
      outcome.chunks++;
      try {
        CinntraPassbookChunkResult result = activities.syncPassbookChunk(kind, chunk);
        if (result != null) {
          outcome.succeeded += result.getSucceeded();
          outcome.failed += result.getFailed();
          outcome.skipped += result.getSkipped();
          outcome.alreadyPosted += result.getAlreadyPosted();
          outcome.notFound += result.getNotFound();
        }
      } catch (ActivityFailure ex) {
        outcome.failedChunks++;
        log.error(
            "event=cinntra_passbook_chunk_failed workflow_id={}"
                + " kind={} chunk_start={} chunk_size={} error={}",
            workflowId,
            kind,
            from,
            chunk.size(),
            ex.getMessage());
      }
    }

    log.info(
        "event=cinntra_passbook_kind_completed workflow_id={}"
            + " kind={} entries={} chunks={} failed_chunks={} "
            + "succeeded={} failed={} skipped={} already_posted={} not_found={}",
        workflowId,
        kind,
        outcome.entries,
        outcome.chunks,
        outcome.failedChunks,
        outcome.succeeded,
        outcome.failed,
        outcome.skipped,
        outcome.alreadyPosted,
        outcome.notFound);
    return outcome;
  }

  /** Totals for one kind within one run. Workflow-local; never serialized. */
  static final class Outcome {
    final CinntraPassbookSyncKind kind;
    boolean selectionFailed;
    int entries;
    int chunks;
    int failedChunks;
    int succeeded;
    int failed;
    int skipped;
    int alreadyPosted;
    int notFound;

    Outcome(CinntraPassbookSyncKind kind) {
      this.kind = kind;
    }

    /**
     * The selection or a chunk activity failed outright — per-entry SAP failures do not count here.
     */
    boolean activityFailed() {
      return selectionFailed || failedChunks > 0;
    }

    String describe() {
      return kind
          + (selectionFailed
              ? " selection failed"
              : " " + failedChunks + " of " + chunks + " chunks failed");
    }
  }
}
