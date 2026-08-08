package com.yesmadam.schedulerservice.jobs.sebaltagging.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.SebalTaggingWatermark;
import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SubCategoryRebookingDelta;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.RankedTagProjection;
import com.yesmadam.schedulerservice.jobs.sebaltagging.repository.SebalTaggingBatchDao;
import com.yesmadam.schedulerservice.jobs.sebaltagging.repository.SebalTaggingWatermarkRepository;

/**
 * Persists SEBAL sub-category tagging results.
 *
 * <p>Every public method here is its own small, independently-committing {@code @Transactional}
 * unit — kept as a separate bean (rather than methods on {@link SebalTaggingServiceImpl}) so each
 * one goes through the Spring proxy; self-invocation would silently bypass transaction management
 * (same reasoning as {@code RoseOrderEligibilityWriter}). {@link SebalTaggingServiceImpl}
 * orchestrates the sequence — that separation is what lets the finalize step batch its writes
 * instead of holding one transaction over everything it touches.
 *
 * <p>The transaction boundaries live here; the statements themselves live in {@link
 * SebalTaggingBatchDao}, which issues them as multi-row native SQL rather than entity-at-a-time
 * {@code saveAll}. Both entities use {@code GenerationType.IDENTITY}, which makes Hibernate batch
 * inserts impossible — see that class for the full reasoning and the measured cost.
 */
@Service
public class SebalTaggingWriter {

  /** Singleton watermark row id — this job tracks one global cursor, not one per sub-category. */
  static final Long WATERMARK_ID = 1L;

  private final SebalTaggingWatermarkRepository watermarkRepository;
  private final SebalTaggingBatchDao batchDao;

  public SebalTaggingWriter(
      SebalTaggingWatermarkRepository watermarkRepository, SebalTaggingBatchDao batchDao) {
    this.watermarkRepository = watermarkRepository;
    this.batchDao = batchDao;
  }

  /**
   * Loads the current watermark row, creating the singleton row on first-ever run rather than
   * treating "no row" as its own state everywhere else in the job.
   */
  @Transactional
  public SebalTaggingWatermark loadOrCreateWatermark() {
    return watermarkRepository
        .findById(WATERMARK_ID)
        .orElseGet(
            () -> {
              SebalTaggingWatermark watermark = new SebalTaggingWatermark();
              watermark.setId(WATERMARK_ID);
              return watermarkRepository.save(watermark);
            });
  }

  /**
   * Merges one chunk's per-(SP, sub-category) deltas onto the running aggregate and appends one
   * append-only {@code DELTA} ledger row per pair touched. The aggregate upsert and the ledger
   * append commit together, so a retry of this chunk never double-counts and never leaves the two
   * tables inconsistent.
   *
   * <p>Cumulative counts, rate, rank, and tier are deliberately left at their defaults on these
   * rows — they're not knowable until every chunk in the run has merged and ranking has run (see
   * {@link #insertSummaryLedgerRows}), and this row is never updated later to fill them in; that's
   * exactly the "ledger secretly isn't append-only" problem this shape avoids.
   *
   * <p>Both writes go through {@link SebalTaggingBatchDao} as multi-row statements. The aggregate
   * merge happens inside MySQL against the {@code (sp_id, sub_category_id)} unique constraint, so
   * there is no longer a read-before-write to load the existing counts.
   *
   * @param deltas this chunk's contribution, already grouped by (sp, sub-category)
   * @param runMode the mode this run is operating in
   * @param runId the workflow run id shared by every row this run produces
   * @return number of distinct (sp, sub-category) pairs this chunk touched
   */
  @Transactional
  public int mergeChunkDeltas(
      List<SubCategoryRebookingDelta> deltas, SebalTaggingRunMode runMode, String runId) {
    if (deltas.isEmpty()) {
      return 0;
    }

    batchDao.upsertTagDeltas(deltas, runMode, runId);
    batchDao.insertDeltaLedgerRows(runId, runMode, deltas);
    return deltas.size();
  }

  /**
   * Zeroes one batch of rows no chunk of this FULL run touched. Its own transaction, so a sweep
   * over a large table never holds one long write lock; the caller loops until it returns 0.
   *
   * @return rows swept in this batch
   */
  @Transactional
  public int resetStaleRowsBatch(String runId) {
    return batchDao.resetRowsNotTouchedByRun(runId);
  }

  /**
   * Applies one batch of already-computed rank/tier values. Its own transaction, independent of
   * every other batch — so a run touching many sub-categories doesn't hold one long write lock
   * over all of them, and a failure partway through only needs this batch (and any after it)
   * re-applied, which is safe since {@link SebalTaggingReader#computeRanks} is a pure function of
   * the current counts.
   */
  @Transactional
  public void applyRankBatch(List<RankedTagProjection> batch) {
    batchDao.applyRanks(batch);
  }

  /**
   * Appends one {@code SUMMARY} ledger row per ranked pair — the final cumulative counts/rate/
   * rank/tier for this run, as their own new rows. Never touches the {@code DELTA} rows the same
   * run's chunks already wrote.
   */
  @Transactional
  public void insertSummaryLedgerRows(
      String runId, SebalTaggingRunMode runMode, List<RankedTagProjection> ranked) {
    batchDao.insertSummaryLedgerRows(runId, runMode, ranked);
  }

  /** Advances the watermark cursor — only ever forward, regardless of what called it. */
  @Transactional
  public void advanceWatermark(SebalTaggingRunMode runMode, String runId, LocalDate newProcessedUntil) {
    SebalTaggingWatermark watermark = loadOrCreateWatermark();
    if (watermark.getProcessedUntil() == null || newProcessedUntil.isAfter(watermark.getProcessedUntil())) {
      watermark.setProcessedUntil(newProcessedUntil);
    }
    watermark.setLastRunId(runId);
    watermark.setLastRunMode(runMode.name());
    if (runMode == SebalTaggingRunMode.FULL) {
      watermark.setLastFullRecomputeAt(Instant.now());
    }
    watermarkRepository.save(watermark);
  }
}
