package com.yesmadam.schedulerservice.jobs.cinntrapassbook.activity;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookChunkResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSelection;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activities for the three passbook-to-SAP syncs.
 *
 * <p>Chunked shape (CLAUDE.md rules 4, 9, 10, 14): one read-only selection returning ids, then
 * the workflow drives fixed-size chunks, each its own retryable activity that bulk-loads its
 * lookups and heartbeats once per entry posted. A failure costs one chunk, and a retried chunk
 * skips entries an earlier attempt already posted.
 */
@ActivityInterface
public interface CinntraPassbookSyncActivities {

  /** Passbook ids {@code kind} will post for the run scheduled at {@code nominalFireTimeMillis}. */
  @ActivityMethod(name = "CinntraPassbookSelectEntries")
  CinntraPassbookSelection selectPassbookEntries(
      CinntraPassbookSyncKind kind, long nominalFireTimeMillis);

  /** Posts one chunk of {@code kind}'s entries to SAP. Failures are isolated per entry. */
  @ActivityMethod(name = "CinntraPassbookSyncChunk")
  CinntraPassbookChunkResult syncPassbookChunk(
      CinntraPassbookSyncKind kind, List<Long> passbookIds);
}
