package com.yesmadam.schedulerservice.jobs.cinntrapassbook.activity;

import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookChunkResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSelection;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.service.CinntraPassbookSyncService;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;

/**
 * Thin adapter between Temporal and the service layer — all logic lives in {@link
 * CinntraPassbookSyncService}. The only Temporal-specific work here is turning the service's
 * progress reports into heartbeats.
 *
 * <p>Registered on both tiers because the three workflows sharing it are split across them —
 * commission/convenience on heavy, journal and wallet/booking-online on standard. The stub sets no
 * task queue, so each workflow's activities run on its own tier.
 */
@Component
@ActivityImpl(taskQueues = {"${temporal.taskqueue.heavy}", "${temporal.taskqueue.standard}"})
public class CinntraPassbookSyncActivitiesImpl implements CinntraPassbookSyncActivities {

  private final CinntraPassbookSyncService syncService;

  public CinntraPassbookSyncActivitiesImpl(CinntraPassbookSyncService syncService) {
    this.syncService = syncService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "select-cinntra-passbook-entries")
  @HandleExceptions
  public CinntraPassbookSelection selectPassbookEntries(
      CinntraPassbookSyncKind kind, long nominalFireTimeMillis) {
    return syncService.selectPassbookIds(kind, nominalFireTimeMillis);
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "sync-cinntra-passbook-chunk")
  @HandleExceptions
  public CinntraPassbookChunkResult syncPassbookChunk(
      CinntraPassbookSyncKind kind, List<Long> passbookIds) {
    return syncService.syncChunk(kind, passbookIds, heartbeat());
  }

  private static ProgressReporter heartbeat() {
    return completed -> Activity.getExecutionContext().heartbeat(completed);
  }
}
