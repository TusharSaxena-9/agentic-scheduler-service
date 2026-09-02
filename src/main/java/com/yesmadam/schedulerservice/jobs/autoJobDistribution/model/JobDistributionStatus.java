package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

/**
 * Lifecycle states for a single round in {@code tbl_job_distribution}. Ported verbatim from cron's
 * {@code com.ym.enumu.JobDistributionStatus} (SAAB-349) — the values are persisted as strings, so
 * renaming or reordering any of them rewrites history.
 *
 * <pre>
 * DISTRIBUTED      round is live, SPs notified, awaiting acceptance (is_active = 1)
 * ACCEPTED         an SP accepted this round (closed, is_active = NULL)
 * EXPIRED          window elapsed and superseded by the next round — OR the order's own slot had
 *                  already passed when the round was evaluated, closed terminally with no next round
 * FINAL_UNASSIGNED no acceptance after the final round, or no SP left to notify
 * CANCELLED        the order itself was cancelled while this round was live
 * SUPERSEDED       the order was assigned outside the distribution flow (e.g. manual reassign)
 * RESCHEDULED      the order's booking_date moved away from today while this round was live
 * </pre>
 */
public enum JobDistributionStatus {
  DISTRIBUTED,
  ACCEPTED,
  EXPIRED,
  FINAL_UNASSIGNED,
  CANCELLED,
  SUPERSEDED,
  RESCHEDULED
}
