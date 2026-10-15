package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.activity;

import java.util.List;
import java.util.Map;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncChunkResult;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncRunContext;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activities for the elite-fee-mismatch-to-SAP sync.
 *
 * <p>Chunked shape (CLAUDE.md rules 4, 9, 10, 14): one read-only prepare step, then the workflow
 * drives fixed-size chunks, each its own retryable activity that heartbeats per subscription. A
 * failure costs one chunk, and a retried chunk skips subscriptions an earlier attempt already
 * posted to SAP.
 */
@ActivityInterface
public interface EliteMismatchSyncActivities {

  /**
   * Yesterday's Elite subscriptions with no {@code tbl_sap_doc_entries} row (tracking's {@code
   * getSubscriptionOrderIdsNotInSapDoc}), plus the fee map and plan amount tracking resolves once
   * per run.
   */
  @ActivityMethod
  EliteMismatchSyncRunContext prepareEliteMismatchRun();

  /**
   * Posts a SAP BusinessPartner (if needed) + AR Invoice + Incoming Payment for each subscription
   * in the chunk. Failures are isolated per subscription.
   */
  @ActivityMethod
  EliteMismatchSyncChunkResult syncEliteMismatchChunk(
      List<Integer> subscriptionIds,
      Map<Integer, Integer> eliteFeeBySubscriptionId,
      Integer fallbackPlanAmount);
}
