package com.yesmadam.schedulerservice.jobs.producthublivestock.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One run's findings: when the check ran and every hub/product combination flagged in it.
 *
 * <p>{@code detectedAt} is a pre-formatted string (e.g. {@code "16th June, 2026 00:00:00"}),
 * rendered once in Asia/Kolkata at fetch time — not a {@code Timestamp}/{@code Instant}. This report
 * crosses the activity boundary multiple times (fetch to mail, fetch to WhatsApp), and each hop is
 * JSON-serialized by Temporal's data converter; a zone-naive {@code Timestamp} round-trips correctly
 * only within one JVM's default zone; if that data converter round trip inside a worker with a
 * different default zone the displayed time would silently shift. A plain string sidesteps that.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveStockAlertReport {

  private String detectedAt;
  private List<LiveStockAlertRow> rows;

  /** Wall-clock time the lookup query took, keyed by query name — see rule 15 in CLAUDE.md. */
  private Map<String, Long> queryDurationsMs;

  /** Row count the lookup query returned, keyed by query name. */
  private Map<String, Integer> queryRowCounts;

  public LiveStockAlertReport(String detectedAt, List<LiveStockAlertRow> rows) {
    this(detectedAt, rows, new LinkedHashMap<>(), new LinkedHashMap<>());
  }
}
