package com.yesmadam.schedulerservice.jobs.autoassign.enums;

/**
 * Which booking day one auto-assign run targets. Two crons in {@code AutoAssignComponent} feed Auto
 * Job Distribution, and they differ in more than the date — this enum is what carries those
 * differences through the workflow so one pipeline can serve both.
 *
 * <p>The differences are small but each one is load-bearing:
 *
 * <table border="1">
 *   <caption>D0 vs D-1</caption>
 *   <tr><th></th><th>{@link #SAME_DAY}</th><th>{@link #NEXT_DAY}</th></tr>
 *   <tr><td>cron source</td>
 *       <td>{@code findNonAssignJobAndAssignAServiceProviderToJob}</td>
 *       <td>{@code findNonAssignJobAndAssignAServiceProviderToJobTomorrow}</td></tr>
 *   <tr><td>window</td><td>{@code 0 *&#47;5 0-21 * * ?}</td><td>{@code 0 *&#47;5 16-23 * * ?}</td></tr>
 *   <tr><td>booking date</td><td>today</td><td>today + 1</td></tr>
 *   <tr><td>lead-time floor</td><td>{@code start >= now + minLeadTime}</td><td><b>none</b></td></tr>
 *   <tr><td>SP-rejection bypass</td><td>not applied</td><td><b>applied</b></td></tr>
 *   <tr><td>express last-resort bypass</td><td><b>applied</b></td><td>not applied</td></tr>
 * </table>
 *
 * <p>Serialized into Temporal workflow history as the workflow's argument, so the name of each
 * constant is part of that history — renaming one changes how an in-flight run deserialises.
 */
public enum AutoAssignScope {

  /**
   * Today's bookings. A non-express candidate must still have {@code minLeadTimeMinutes} of runway;
   * an order flagged {@code is_express_job} may bypass that floor entirely as a last resort — see
   * {@link #isExpressBypassApplied()}.
   *
   * <p>Cron's own same-day floor has since been reduced to zero lead time (no more {@code
   * now().plusHours(1)} — see {@code OrderDaoImpl}), in the same change that introduced the express
   * carve-out this scope now also applies. This enum's {@code minLeadTimeMinutes} default was left
   * at 60 rather than matched to zero, since only the express gap was in scope for this change.
   */
  SAME_DAY(0, true, false, true),

  /**
   * Tomorrow's bookings.
   *
   * <p>No lead-time floor: the cron DAO skips that restriction entirely for a non-today booking
   * date, and reasonably so — every D-1 order is more than an hour out by construction, so applying
   * it would be a no-op that only narrows the plan.
   *
   * <p>Applies the D-1-only SP-rejection bypass: an order whose assigned SP rejected it after
   * today's cutoff hour goes straight to distribution regardless of reassign count, because there
   * is no runway left today for further reassign attempts before this cron's own 16:00 start.
   *
   * <p>Does <b>not</b> apply the express last-resort bypass. Every D-1 order is, by construction,
   * hours from its slot when this scope's window runs, so the "about to start" case the bypass
   * exists for does not arise here — and scoping it out keeps this change from also altering
   * tomorrow's job, which was not part of the request that added it.
   */
  NEXT_DAY(1, false, true, false);

  private final int dayOffset;
  private final boolean leadTimeFloorApplied;
  private final boolean spRejectionBypassApplied;
  private final boolean expressBypassApplied;

  AutoAssignScope(
      int dayOffset,
      boolean leadTimeFloorApplied,
      boolean spRejectionBypassApplied,
      boolean expressBypassApplied) {
    this.dayOffset = dayOffset;
    this.leadTimeFloorApplied = leadTimeFloorApplied;
    this.spRejectionBypassApplied = spRejectionBypassApplied;
    this.expressBypassApplied = expressBypassApplied;
  }

  /** Days to add to today to get this scope's booking date. */
  public int getDayOffset() {
    return dayOffset;
  }

  /** Whether the candidate query filters on a minimum start time. */
  public boolean isLeadTimeFloorApplied() {
    return leadTimeFloorApplied;
  }

  /** Whether the gate diverts orders whose SP rejected them after today's cutoff. */
  public boolean isSpRejectionBypassApplied() {
    return spRejectionBypassApplied;
  }

  /** Whether the gate lets a fresh, about-to-start express order bypass the normal safety checks. */
  public boolean isExpressBypassApplied() {
    return expressBypassApplied;
  }
}
