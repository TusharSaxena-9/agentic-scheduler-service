package com.yesmadam.schedulerservice.jobs.autoassign.model;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.autoassign.enums.AutoAssignScope;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the workflow needs to drive one auto-assign run: the booking date it resolved to, the
 * candidate orders to act on, and the gates to apply. Fetched by a single activity so {@code today}
 * is resolved on an activity thread (never in workflow code, which must stay deterministic) and the
 * values are recorded in workflow history, stable across retries and replays.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AutoAssignRunContext {

  /** The booking date this run targets, ISO-8601 — recorded so history shows what "today" meant. */
  private String bookingDate;

  /** Unassigned orders on {@code bookingDate}, chunked by the workflow. */
  private List<AutoAssignCandidate> candidates;

  /** Auto-assign gates resolved from settings for this run. */
  private AutoAssignThresholds thresholds;

  /**
   * Which booking day this run targets. Carried through to every chunk because the gate itself
   * differs by scope — D-1 applies one extra term the same-day run must not.
   */
  private AutoAssignScope scope;
}
