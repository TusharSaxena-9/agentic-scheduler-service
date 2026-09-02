package com.yesmadam.schedulerservice.common.progress;

/**
 * How a service reports incremental progress out of a long loop, without knowing what consumes it.
 *
 * <p>Exists because of two rules pulling in opposite directions. Rule 10 says anything that can
 * exceed ~1 minute must call {@code Activity.getExecutionContext().heartbeat(...)} — and that call
 * only works on the activity thread. Migration rule 5 says the service layer imports nothing from
 * {@code io.temporal}, which is what keeps the business logic testable with plain JUnit and movable
 * between callers. A one-method seam satisfies both: the service reports, the activity adapter
 * translates the report into a heartbeat, and a unit test passes {@link #noOp()}.
 *
 * <p><b>Why heartbeats matter here specifically.</b> Without one, an activity that wedges — a hung
 * socket, a lock wait that never returns — is only noticed when its {@code startToCloseTimeout}
 * expires. For a job on a `SKIP`-overlap schedule that means the slot and its database connection
 * are held for the whole timeout, and every tick that fires in the meantime is dropped silently: no
 * failed workflow, no alert. A heartbeat timeout turns that into a failure in seconds rather than
 * minutes, and it is also how cancellation reaches a running activity at all.
 *
 * <p>The value passed is a count of units finished so far, which Temporal stores as heartbeat
 * details and hands back to the next attempt via {@code
 * Activity.getExecutionContext().getHeartbeatDetails(...)}. Nothing here reads it back yet — the
 * chunks are sized so a retry from the top is cheap — but recording it costs nothing and is what a
 * resumable retry would need.
 */
@FunctionalInterface
public interface ProgressReporter {

  /**
   * Reports that {@code completed} units of work have finished.
   *
   * <p>Implementations must be cheap. They are <b>allowed to throw, and the caller must not swallow
   * it</b> — which is the opposite of what "reporting is only diagnostics" would suggest.
   * Temporal's {@code heartbeat(...)} throws {@code ActivityCompletionException} when the activity
   * has been cancelled or has already timed out, and that exception <em>is</em> how cancellation
   * reaches a running activity (rule 10). Catching it would leave the loop grinding on work
   * Temporal has already given up on — the exact leak heartbeating exists to prevent.
   *
   * @param completed units finished so far within the current attempt
   */
  void report(int completed);

  /** A reporter that discards everything — for unit tests and any caller outside an activity. */
  static ProgressReporter noOp() {
    return completed -> {
      // Intentionally empty: nothing outside a Temporal activity has anywhere to send this.
    };
  }
}
