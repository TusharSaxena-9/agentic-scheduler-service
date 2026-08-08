package com.yesmadam.schedulerservice.jobs.sebaltagging.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.yesmadam.persistence.SebalTaggingWatermark;
import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingConfig;
import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.DateRangeChunk;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.QualifyingFirstOrderRow;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingChunkResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingRunContext;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingTierResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SpSubCategoryKey;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SubCategoryRebookingDelta;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.QualifyingFirstOrderProjection;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.RankedTagProjection;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.SubsequentOrderProjection;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SebalTaggingServiceImpl implements SebalTaggingService {

  /** A first-order's rebook window: any repeat completion within this many days counts. */
  private static final int REBOOK_WINDOW_DAYS = 90;

  /**
   * A first-order only counts once its rebook window has fully closed — i.e. its completion date
   * is at least {@link #REBOOK_WINDOW_DAYS} days in the past. This is the exact-day equivalent of
   * the PRD's "current month − 3" population cutoff, applied per-day instead of per-month so an
   * incremental run can advance by exactly the days that newly closed since the last run.
   */
  private static final int CUTOFF_LAG_DAYS = REBOOK_WINDOW_DAYS;

  private static final LocalDate POPULATION_START = LocalDate.of(2022, 1, 1);

  private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  /**
   * Rows per {@code applyRankBatch} transaction in the finalize step — keeps each write short at
   * 50k+ SPs across hundreds of sub-categories, rather than one transaction holding locks over
   * everything a run's ranking pass touches. Each transaction is now a handful of multi-row
   * statements rather than one per row, so this can sit well above the DAO's own per-statement
   * cap without lengthening how long any single batch holds its locks.
   */
  private static final int RANK_APPLY_BATCH_SIZE = 2000;

  /**
   * User ids per {@code findCompletedOrdersForRebookCheck} call — sized to stay under the ~11,000-id
   * point where that query's plan flips off the index. See {@link #fetchRebookCandidates}.
   */
  private static final int REBOOK_USER_ID_BATCH_SIZE = 5000;

  private final SebalTaggingReader reader;
  private final SebalTaggingWriter writer;
  private final SettingService settingService;

  public SebalTaggingServiceImpl(
      SebalTaggingReader reader, SebalTaggingWriter writer, SettingService settingService) {
    this.reader = reader;
    this.writer = writer;
    this.settingService = settingService;
  }

  @Override
  public SebalTaggingRunContext fetchRunContext() {
    SebalTaggingWatermark watermark = writer.loadOrCreateWatermark();
    LocalDate cutoff = LocalDate.now(ZONE).minusDays(CUTOFF_LAG_DAYS);

    boolean firstEverRun = watermark.getProcessedUntil() == null;

    // No cursor to take a delta from yet — first-ever run is always FULL regardless of the
    // configured mode, whatever tbl_settings currently says.
    SebalTaggingRunMode runMode;
    if (firstEverRun) {
      runMode = SebalTaggingRunMode.FULL;
    } else {
      Map<String, SettingsForEnalbeDisable> config =
          settingService.findSettingWithKeys(List.of(SebalTaggingConfig.RUN_MODE.key()));
      runMode = SebalTaggingConfig.RUN_MODE.resolve(config);
    }

    List<DateRangeChunk> chunks =
        runMode == SebalTaggingRunMode.FULL
            ? monthlyChunks(POPULATION_START, cutoff)
            : incrementalChunk(watermark.getProcessedUntil(), cutoff);

    log.info(
        "SEBAL tagging run context: mode={} chunks={} cutoff={} firstEverRun={}",
        runMode,
        chunks.size(),
        cutoff,
        firstEverRun);

    return new SebalTaggingRunContext(runMode, chunks, cutoff);
  }

  private static List<DateRangeChunk> monthlyChunks(LocalDate start, LocalDate cutoff) {
    List<DateRangeChunk> chunks = new ArrayList<>();
    LocalDate chunkStart = start;
    while (!chunkStart.isAfter(cutoff)) {
      LocalDate chunkEnd = chunkStart.with(TemporalAdjusters.lastDayOfMonth());
      if (chunkEnd.isAfter(cutoff)) {
        chunkEnd = cutoff;
      }
      chunks.add(new DateRangeChunk(chunkStart, chunkEnd));
      chunkStart = chunkEnd.plusDays(1);
    }
    return chunks;
  }

  private static List<DateRangeChunk> incrementalChunk(LocalDate processedUntil, LocalDate cutoff) {
    LocalDate chunkStart = processedUntil.plusDays(1);
    return chunkStart.isAfter(cutoff) ? List.of() : List.of(new DateRangeChunk(chunkStart, cutoff));
  }

  @Override
  public SebalTaggingChunkResult processChunk(
      DateRangeChunk chunk, SebalTaggingRunMode runMode, String runId, Runnable heartbeat) {

    QueryTimer timer = new QueryTimer();

    List<QualifyingFirstOrderRow> rows =
        timer
            .time(
                "findQualifyingFirstOrders",
                () -> reader.findQualifyingFirstOrders(chunk.getStart(), chunk.getEnd()))
            .stream()
            .map(SebalTaggingServiceImpl::toRow)
            .toList();

    if (rows.isEmpty()) {
      return new SebalTaggingChunkResult(0, 0, timer.getDurationsMs(), timer.getRowCounts());
    }

    heartbeat.run();

    resolveRebookFlags(rows, chunk, timer, heartbeat);

    heartbeat.run();

    List<SubCategoryRebookingDelta> deltas = toDeltas(rows);
    int pairsUpdated = writer.mergeChunkDeltas(deltas, runMode, runId);

    return new SebalTaggingChunkResult(
        rows.size(), pairsUpdated, timer.getDurationsMs(), timer.getRowCounts());
  }

  private void resolveRebookFlags(
      List<QualifyingFirstOrderRow> rows,
      DateRangeChunk chunk,
      QueryTimer timer,
      Runnable heartbeat) {
    List<Long> userIds = rows.stream().map(QualifyingFirstOrderRow::getUserId).distinct().toList();
    LocalDate lookaheadEnd = chunk.getEnd().plusDays(REBOOK_WINDOW_DAYS);

    Map<Long, List<SubsequentOrderProjection>> subsequentOrdersByUser =
        timer
            .time(
                "findCompletedOrdersForRebookCheck",
                () -> fetchRebookCandidates(userIds, chunk.getStart(), lookaheadEnd, heartbeat))
            .stream()
            .collect(Collectors.groupingBy(SubsequentOrderProjection::getUserId));

    for (QualifyingFirstOrderRow row : rows) {
      LocalDate deadline = row.getBookingDate().plusDays(REBOOK_WINDOW_DAYS);
      boolean rebooked =
          subsequentOrdersByUser
              .getOrDefault(row.getUserId(), List.of())
              .stream()
              .filter(candidate -> !candidate.getOrderId().equals(row.getOrderId()))
              .anyMatch(
                  candidate ->
                      candidate.getBookingDate().isAfter(row.getBookingDate())
                          && !candidate.getBookingDate().isAfter(deadline));
      row.setRebooked(rebooked);
    }
  }

  /**
   * Fetches the rebook-check candidates in bounded id batches rather than one giant {@code IN}
   * list.
   *
   * <p>Measured on the 2026-07-26 FULL run, this query is bimodal: ~300ms up to roughly 11,000 user
   * ids, ~3,400ms above it. 2023-04 with 10,798 ids took 308ms; 2023-05 with 11,185 took 3,328ms.
   * That is the optimizer abandoning the index once the {@code IN} list crosses a cost threshold —
   * the same failure that made the old dominant-sub-category lookup cost a flat ~104s per chunk.
   * Every chunk from 2024-02 onward was on the wrong side of it, ~130s across the run.
   *
   * <p>Batching keeps every call below the threshold, so each stays on the fast plan. Results are
   * concatenated; the caller groups by user id, so batch boundaries don't matter — a user's rows
   * all land in the same batch regardless, since batching is by user id.
   */
  private List<SubsequentOrderProjection> fetchRebookCandidates(
      List<Long> userIds, LocalDate lookaheadStart, LocalDate lookaheadEnd, Runnable heartbeat) {
    List<SubsequentOrderProjection> candidates = new ArrayList<>();
    for (List<Long> batch : Lists.partition(userIds, REBOOK_USER_ID_BATCH_SIZE)) {
      candidates.addAll(
          reader.findCompletedOrdersForRebookCheck(batch, lookaheadStart, lookaheadEnd));
      heartbeat.run();
    }
    return candidates;
  }

  /** Collapses rows to one delta per (SP, sub-category): total count and rebooked-within-90d count. */
  private static List<SubCategoryRebookingDelta> toDeltas(List<QualifyingFirstOrderRow> rows) {
    Function<QualifyingFirstOrderRow, SpSubCategoryKey> byPair =
        row -> new SpSubCategoryKey(row.getSpId(), row.getSubCategoryId());

    Map<SpSubCategoryKey, Long> firstJobCounts =
        rows.stream().collect(Collectors.groupingBy(byPair, Collectors.counting()));
    Map<SpSubCategoryKey, Long> rebookedCounts =
        rows.stream()
            .filter(QualifyingFirstOrderRow::isRebooked)
            .collect(Collectors.groupingBy(byPair, Collectors.counting()));

    return firstJobCounts.entrySet().stream()
        .map(
            entry ->
                new SubCategoryRebookingDelta(
                    entry.getKey().spId(),
                    entry.getKey().subCategoryId(),
                    entry.getValue(),
                    rebookedCounts.getOrDefault(entry.getKey(), 0L)))
        .toList();
  }

  @Override
  public SebalTaggingTierResult finalizeRun(
      SebalTaggingRunMode runMode, String runId, LocalDate newProcessedUntil, Runnable heartbeat) {

    if (runMode == SebalTaggingRunMode.FULL) {
      sweepRowsNotTouchedByRun(runId, heartbeat);
    }

    List<Integer> touchedSubCategoryIds = reader.findTouchedSubCategoryIds(runId);
    List<RankedTagProjection> ranked = reader.computeRanks(touchedSubCategoryIds);

    logRankingScope(touchedSubCategoryIds, ranked);
    heartbeat.run();

    for (List<RankedTagProjection> batch : Lists.partition(ranked, RANK_APPLY_BATCH_SIZE)) {
      writer.applyRankBatch(batch);
      heartbeat.run();
    }

    writer.insertSummaryLedgerRows(runId, runMode, ranked);
    heartbeat.run();
    writer.advanceWatermark(runMode, runId, newProcessedUntil);

    if (runMode == SebalTaggingRunMode.FULL) {
      revertRunModeToIncremental();
    }

    return reader.countTierResult();
  }

  /**
   * Records how much of the touched population actually got ranked.
   *
   * <p>Ranking is scoped to the Rose Order SP pool (see {@code
   * SpSebalSubCategoryTagRepository#computeRanksForSubCategories}), so an unpopulated
   * {@code tbl_sp_rose_order_eligibilities} produces a run that processes every chunk, reports
   * success, and writes no tiers at all — leaving SEBAL to fall back to standard Slot Priority for
   * every order. That failure mode is invisible without this warning, since nothing else about the
   * run looks wrong.
   */
  private void logRankingScope(
      List<Integer> touchedSubCategoryIds, List<RankedTagProjection> ranked) {
    if (touchedSubCategoryIds.isEmpty()) {
      return;
    }
    if (ranked.isEmpty()) {
      log.warn(
          "event=sebal_no_pooled_sps_ranked sub_categories_touched={} ranked_rows=0 "
              + "reason=no_sp_in_rose_order_eligibility_pool_has_a_qualifying_cohort "
              + "impact=no_tiers_written_sebal_will_fall_back_to_slot_priority",
          touchedSubCategoryIds.size());
      return;
    }
    log.info(
        "event=sebal_ranking_scope sub_categories_touched={} ranked_rows={}",
        touchedSubCategoryIds.size(),
        ranked.size());
  }

  /**
   * Flips {@code SEBAL_TAGGING_RUN_MODE} back to {@code INCREMENTAL} now that this FULL run has
   * finished — see {@link SebalTaggingConfig#RUN_MODE}, which documents FULL as a one-shot
   * trigger.
   *
   * <p>Runs last, after the watermark has advanced, so a run that dies earlier leaves the setting
   * on FULL and the next run picks the rebuild back up.
   *
   * <p>Failing to flip it is logged, not thrown: the rebuild itself has already committed, and
   * failing the activity here would have Temporal retry the whole finalize step over a bookkeeping
   * write. The cost of the flip not landing is one redundant FULL run next time, which is
   * self-correcting and far cheaper than losing a completed rebuild.
   */
  private void revertRunModeToIncremental() {
    try {
      boolean updated =
          settingService.updateSettingValue(
              SebalTaggingConfig.RUN_MODE.key(), SebalTaggingRunMode.INCREMENTAL.name());
      if (updated) {
        log.info(
            "event=sebal_run_mode_reset key={} value={}",
            SebalTaggingConfig.RUN_MODE.key(),
            SebalTaggingRunMode.INCREMENTAL);
      } else {
        log.warn(
            "event=sebal_run_mode_reset_skipped reason=setting_row_missing key={}",
            SebalTaggingConfig.RUN_MODE.key());
      }
    } catch (RuntimeException e) {
      log.error(
          "event=sebal_run_mode_reset_failed key={} error={} — next scheduled run will repeat FULL",
          SebalTaggingConfig.RUN_MODE.key(),
          e.getMessage(),
          e);
    }
  }

  /**
   * Zeroes every aggregate row this FULL run never wrote to, in small independently-committing
   * batches, before ranking sees them.
   *
   * <p>A pair with no qualifying orders left is never visited by any chunk, so without this it
   * would keep the counts and tier from whenever it last had data — the one way a FULL run could
   * still leave stale state behind. Runs before ranking so the zeroed rows drop out of
   * {@code computeRanksForSubCategories}, which only considers rows with a non-zero sample.
   */
  private void sweepRowsNotTouchedByRun(String runId, Runnable heartbeat) {
    int totalSwept = 0;
    int swept;
    do {
      swept = writer.resetStaleRowsBatch(runId);
      totalSwept += swept;
      heartbeat.run();
    } while (swept > 0);

    if (totalSwept > 0) {
      log.info("event=sebal_stale_rows_reset run_id={} rows={}", runId, totalSwept);
    }
  }

  private static QualifyingFirstOrderRow toRow(QualifyingFirstOrderProjection p) {
    return new QualifyingFirstOrderRow(
        p.getOrderId(),
        p.getUserId(),
        p.getSpId(),
        p.getBookingDate(),
        p.getSubCategoryId(),
        false);
  }
}
