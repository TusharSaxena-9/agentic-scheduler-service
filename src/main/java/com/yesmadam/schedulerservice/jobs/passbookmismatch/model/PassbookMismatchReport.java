package com.yesmadam.schedulerservice.jobs.passbookmismatch.model;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One run's findings: the window audited and every row flagged in it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PassbookMismatchReport {

  private Timestamp windowStart;
  private Timestamp windowEnd;
  private List<PassbookMismatchRow> rows;

  /**
   * Wall-clock time the audit query took, keyed by query name.
   *
   * <p>Carried on the result rather than only logged so it lands in workflow history and is
   * readable per run in the Temporal UI — same as {@code RoseOrderChunkResult} and {@code
   * SebalTaggingChunkResult}. This is how the query's real cost gets tracked: measured on prod at
   * 1,735 ms for a 30-minute window before any index work, and that number is the reason the {@code
   * (spid, id)} index question is still open.
   */
  private Map<String, Long> queryDurationsMs;

  /** Row count the audit query returned, keyed by query name. */
  private Map<String, Integer> queryRowCounts;

  public PassbookMismatchReport(
      Timestamp windowStart, Timestamp windowEnd, List<PassbookMismatchRow> rows) {
    this(windowStart, windowEnd, rows, new LinkedHashMap<>(), new LinkedHashMap<>());
  }
}
