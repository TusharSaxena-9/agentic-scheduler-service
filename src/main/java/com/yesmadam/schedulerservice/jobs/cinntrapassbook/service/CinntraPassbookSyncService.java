package com.yesmadam.schedulerservice.jobs.cinntrapassbook.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookChunkResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookLookups;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapResult;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSelection;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSyncKind;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookEntryRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookReadRepository;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository.CinntraPassbookWriteRepository;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

/**
 * Ports tracking's {@code CinntraPassbook} cron — the three {@code @Scheduled} methods that post
 * {@code tbl_sp_passbook} movements to SAP through Cinntra — onto the heavy tier.
 *
 * <p>Shape, per run: one read-only selection of passbook ids, then chunks. Each chunk loads
 * everything it needs in a handful of bulk queries ({@link CinntraPassbookReader}) and then posts
 * its entries to SAP one at a time, reporting progress after each so the activity heartbeats.
 * Tracking makes four to eight single-row queries per entry between SAP calls; here a chunk of 20
 * entries makes about ten queries in all, none of them while a SAP call is in flight.
 *
 * <p>Retry-safe (CLAUDE.md rule 14). A chunk can be re-run from the top after it posted some of its
 * entries, so before posting it re-reads, on the writer, both markers an earlier attempt leaves:
 * the entry's {@code is_sap_processed = 1}, and for the daily kinds a {@code tbl_sap_doc_entries}
 * row for the entry's key. Either one means "already in SAP" and nothing is sent. The second is the
 * dedupe tracking's own {@code PassbookSapSyncService} applies, and it covers the window in which
 * SAP accepted a post but the flag write had not landed.
 *
 * <p>Deliberate differences from tracking, all on the bookkeeping side; what is sent to SAP is not
 * changed:
 *
 * <ul>
 *   <li>The daily kinds set {@code is_sap_processed = 1} on success, as the manual {@code
 *       syncUnprocessedPassbookToSap} path already does. Tracking's cron left it unset, and the
 *       selection queries skip only rows marked processed, so a rerun of the day posted again.
 *   <li>Within a daily kind, one document per key: once an order's commission is in SAP, its other
 *       reason-11 rows are marked processed rather than invoiced again — {@code
 *       PassbookSapSyncService}'s rule, which the cron did not apply.
 *   <li>Windows are tiles keyed off the scheduled time instead of {@code NOW()} at fire time
 *       (CLAUDE.md rule 1), and dates are IST explicitly.
 *   <li>No {@code tbl_cron_time} rows (rule 8); counts and query timings come back in the activity
 *       result instead.
 * </ul>
 */
@Slf4j
@Service
public class CinntraPassbookSyncService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter WINDOW_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /** The journal sync's own tile — tracking's {@code NOW() - INTERVAL 30 MINUTE}. */
  static final int JOURNAL_SLOT_MINUTES = 30;

  /**
   * How far back the journal sync re-tries unprocessed entries — tracking's {@code NOW() - INTERVAL
   * 2 HOUR}.
   */
  static final int JOURNAL_RETRY_LOOKBACK_MINUTES = 120;

  private final CinntraPassbookReadRepository readRepository;
  private final CinntraPassbookWriteRepository writeRepository;
  private final CinntraPassbookReader reader;
  private final CinntraPassbookJournalPoster journalPoster;
  private final CinntraPassbookDocumentPoster documentPoster;

  /** Wires the repositories, the bulk reader and the two posters. */
  public CinntraPassbookSyncService(
      CinntraPassbookReadRepository readRepository,
      CinntraPassbookWriteRepository writeRepository,
      CinntraPassbookReader reader,
      CinntraPassbookJournalPoster journalPoster,
      CinntraPassbookDocumentPoster documentPoster) {
    this.readRepository = readRepository;
    this.writeRepository = writeRepository;
    this.reader = reader;
    this.journalPoster = journalPoster;
    this.documentPoster = documentPoster;
  }

  /**
   * The ids {@code kind} will post for the run scheduled at {@code nominalFireTimeMillis}.
   *
   * <p>Journal: the 30-minute tile ending at the scheduled slot, then every still-unprocessed entry
   * from the 90 minutes before it — tracking's two queries. Because the tile is derived from the
   * schedule, consecutive runs cover the passbook with no gap and no overlap; a run that is skipped
   * entirely is still covered by the retry window of the runs after it.
   *
   * <p>Daily kinds: yesterday in IST, relative to the scheduled date.
   */
  public CinntraPassbookSelection selectPassbookIds(
      CinntraPassbookSyncKind kind, long nominalFireTimeMillis) {
    LocalDateTime scheduled =
        Instant.ofEpochMilli(nominalFireTimeMillis).atZone(IST).toLocalDateTime();
    QueryTimer timer = new QueryTimer();
    List<Long> ids;
    LocalDateTime windowStart;
    LocalDateTime windowEnd;

    if (kind == CinntraPassbookSyncKind.JOURNAL) {
      windowEnd = journalSlotEnd(scheduled);
      LocalDateTime slotStart = windowEnd.minusMinutes(JOURNAL_SLOT_MINUTES);
      windowStart = windowEnd.minusMinutes(JOURNAL_RETRY_LOOKBACK_MINUTES);
      List<Long> current =
          timer.time("findIdsInWindow", () -> readRepository.findIdsInWindow(slotStart, windowEnd));
      List<Long> retry =
          timer.time(
              "findUnprocessedIdsInWindow",
              () -> readRepository.findUnprocessedIdsInWindow(windowStart, slotStart));
      log.info(
          "Fetched {} SpPassbook records from the last 30 minutes;"
              + " retrying {} unprocessed from the last 2 hours.",
          current.size(),
          retry.size());
      Set<Long> ordered = new LinkedHashSet<>(current);
      ordered.addAll(retry);
      ids = new ArrayList<>(ordered);
    } else {
      LocalDate yesterday = scheduled.toLocalDate().minusDays(1);
      windowStart = yesterday.atStartOfDay();
      windowEnd = yesterday.plusDays(1).atStartOfDay();
      ids =
          kind == CinntraPassbookSyncKind.CONVENIENCE
              ? timer.time(
                  "findPendingConvenienceIds",
                  () -> readRepository.findPendingConvenienceIds(windowStart, windowEnd))
              : timer.time(
                  "findUnprocessedIdsByReasonCode",
                  () ->
                      readRepository.findUnprocessedIdsByReasonCode(
                          kind.getReasonCode(), windowStart, windowEnd));
      log.info(
          "Total {} passbook entries to be processed for {} on {}", ids.size(), kind, yesterday);
    }

    return new CinntraPassbookSelection(
        ids,
        windowStart.format(WINDOW_FORMAT),
        windowEnd.format(WINDOW_FORMAT),
        timer.getDurationsMs(),
        timer.getRowCounts());
  }

  /**
   * The :00 or :30 boundary at or before {@code scheduled}, so a few seconds' start delay picks the
   * same tile.
   */
  static LocalDateTime journalSlotEnd(LocalDateTime scheduled) {
    LocalDateTime hour = scheduled.truncatedTo(ChronoUnit.HOURS);
    return scheduled.getMinute() >= JOURNAL_SLOT_MINUTES
        ? hour.plusMinutes(JOURNAL_SLOT_MINUTES)
        : hour;
  }

  /**
   * Posts one chunk of {@code kind}'s entries, in the order given. Failures are isolated per entry,
   * as in tracking: one bad entry is counted and the rest still post.
   *
   * <p>Not transactional: each entry is a SAP HTTP call, and a transaction around it would pin a
   * connection for SAP's response time (rule 13). The lookups are one short read-only transaction;
   * each flag and doc-entry write commits on its own, right after the post it records.
   */
  public CinntraPassbookChunkResult syncChunk(
      CinntraPassbookSyncKind kind, List<Long> passbookIds, ProgressReporter progress) {
    CinntraPassbookChunkResult result = new CinntraPassbookChunkResult();
    if (passbookIds == null || passbookIds.isEmpty()) {
      return result;
    }
    QueryTimer timer = new QueryTimer();

    List<CinntraPassbookEntryRow> entries =
        timer.time("findForPosting", () -> writeRepository.findForPosting(passbookIds));
    Map<Long, CinntraPassbookEntryRow> byId = new HashMap<>();
    for (CinntraPassbookEntryRow entry : entries) {
      byId.put(entry.getId(), entry);
    }
    CinntraPassbookLookups lookups = reader.loadLookups(kind, entries, timer);
    Set<Long> postedKeys = loadPostedKeys(kind, entries, timer);

    int completed = 0;
    for (Long id : passbookIds) {
      CinntraPassbookEntryRow entry = byId.get(id);
      if (entry == null) {
        result.setNotFound(result.getNotFound() + 1);
      } else if (Integer.valueOf(1).equals(entry.getIsSapProcessed())) {
        result.setAlreadyPosted(result.getAlreadyPosted() + 1);
      } else if (kind == CinntraPassbookSyncKind.JOURNAL) {
        journalPoster.post(entry, lookups, result);
      } else {
        postDocument(kind, entry, lookups, postedKeys, result);
      }
      // Outside every catch: a heartbeat that throws is how cancellation reaches this loop.
      progress.report(++completed);
    }

    result.setQueryDurationsMs(timer.getDurationsMs());
    result.setQueryRowCounts(timer.getRowCounts());
    log.info(
        "{} chunk done. Success: {}, Failed: {}, Skipped: {}, Already posted: {}, Not found: {}",
        kind,
        result.getSucceeded(),
        result.getFailed(),
        result.getSkipped(),
        result.getAlreadyPosted(),
        result.getNotFound());
    return result;
  }

  private void postDocument(
      CinntraPassbookSyncKind kind,
      CinntraPassbookEntryRow entry,
      CinntraPassbookLookups lookups,
      Set<Long> postedKeys,
      CinntraPassbookChunkResult result) {
    Long key = kind.docEntryKey(entry);
    if (key != null && postedKeys.contains(key)) {
      markSapProcessed(entry);
      result.setAlreadyPosted(result.getAlreadyPosted() + 1);
      return;
    }
    try {
      CinntraPassbookSapResult sapResult = documentPoster.post(kind, entry, lookups);
      if (sapResult == null) {
        result.setSkipped(result.getSkipped() + 1);
      } else if (sapResult.isSuccess()) {
        if (key != null) {
          postedKeys.add(key);
        }
        markSapProcessed(entry);
        result.setSucceeded(result.getSucceeded() + 1);
      } else {
        result.setFailed(result.getFailed() + 1);
      }
    } catch (Exception e) {
      log.error(
          "{} posting failed for passbook id={}, spId={}, orderId={}",
          kind,
          entry.getId(),
          entry.getSpid(),
          entry.getOrderId(),
          e);
      result.setFailed(result.getFailed() + 1);
    }
  }

  /**
   * Keys of this chunk's entries that already have their SAP document, read fresh from the writer.
   */
  private Set<Long> loadPostedKeys(
      CinntraPassbookSyncKind kind, List<CinntraPassbookEntryRow> entries, QueryTimer timer) {
    if (kind.getDocRequestType() == null) {
      return new HashSet<>();
    }
    // tbl_sap_doc_entries.orderId is an int column; tracking narrows the key the same way when it
    // writes it.
    List<Integer> keys =
        entries.stream()
            .map(kind::docEntryKey)
            .filter(Objects::nonNull)
            .map(Long::intValue)
            .distinct()
            .collect(Collectors.toList());
    if (keys.isEmpty()) {
      return new HashSet<>();
    }
    return timer
        .time(
            "findPostedKeys", () -> writeRepository.findPostedKeys(keys, kind.getDocRequestType()))
        .stream()
        .map(Integer::longValue)
        .collect(Collectors.toCollection(HashSet::new));
  }

  private void markSapProcessed(CinntraPassbookEntryRow entry) {
    try {
      writeRepository.updateSapProcessed(entry.getId(), 1);
      entry.setIsSapProcessed(1);
    } catch (Exception e) {
      log.error("Failed to persist is_sap_processed for SpPassbook ID: {}", entry.getId(), e);
    }
  }
}
