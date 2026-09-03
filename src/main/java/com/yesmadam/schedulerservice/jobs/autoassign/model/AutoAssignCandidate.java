package com.yesmadam.schedulerservice.jobs.autoassign.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One unassigned order the run may act on, with its booking start resolved to an absolute instant.
 *
 * <p>The slot label is parsed once during the context fetch rather than per chunk: the instant an
 * order starts never moves, so it is a stable input worth recording in workflow history, while the
 * "does it start within the priority window" comparison is deliberately left to the chunk activity
 * where {@code now} is read fresh at execution time.
 *
 * <p>Serialized into Temporal workflow history — keep Jackson-friendly (no-args constructor,
 * getters/setters).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AutoAssignCandidate {

  /** {@code tbl_order.order_id}. */
  private Long orderId;

  /**
   * Epoch-millis of the order's booking slot start, or {@code null} when the slot label could not
   * be parsed. A {@code null} is treated as "starts infinitely far away" so an unparseable slot
   * never blocks auto-assign — the cron's parse-failure-is-safe behaviour.
   */
  private Long startTimeMillis;

  /** {@code tbl_order.is_express_job}, resolved to a non-null primitive at the fetch. */
  private boolean expressJob;

  /**
   * Epoch-millis of {@code tbl_order.created_at}, or {@code null} when unresolved. Unlike {@link
   * #startTimeMillis}, unknown here is treated as "created infinitely long ago" — it must fail the
   * express last-resort check closed, not open, so unresolved data can never unlock a bypass that
   * exists specifically for an order just placed.
   */
  private Long createdAtMillis;

  /** Pre-express-carve-out shape: not an express order, creation time not tracked. */
  public AutoAssignCandidate(Long orderId, Long startTimeMillis) {
    this(orderId, startTimeMillis, false, null);
  }
}
