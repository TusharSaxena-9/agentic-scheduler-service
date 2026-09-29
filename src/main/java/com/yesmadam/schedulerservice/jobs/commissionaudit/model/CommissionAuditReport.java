package com.yesmadam.schedulerservice.jobs.commissionaudit.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One run's findings: the booking date audited, every flagged order, and the count per reason.
 *
 * <p>Rows are not capped. Each row is three small fields, so even a night when the 03:00 deduction
 * cron fails outright (a few thousand orders) stays well under Temporal's 2 MB payload limit.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommissionAuditReport {

  /** ISO date ({@code yyyy-MM-dd}, Asia/Kolkata) whose bookings were audited. */
  private String bookingDate;

  private List<CommissionAuditRow> rows;

  /** Flagged order count keyed by reason. */
  private Map<String, Long> countsByReason;

  /** Wall-clock time of the audit query, keyed by query name. */
  private Map<String, Long> queryDurationsMs;

  /** Row count the audit query returned, keyed by query name. */
  private Map<String, Integer> queryRowCounts;
}
