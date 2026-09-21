package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

/**
 * Action names this package writes into {@code tbl_sp_update_action_hstry}.
 *
 * <p>Shared constants because each is used in two places that must agree: the writer that inserts
 * the row, and the query that filters on it. A literal repeated across those two would drift, and
 * the failure would be silent — a dedup filter that matches nothing re-notifies everyone.
 *
 * <p>Both are distinct from {@code USER_AVAILABILITY}, the admin panel's manual toggle, so an
 * operator reading an SP's timeline can tell a nightly automated change from someone's decision.
 * That follows the precedent {@code INSTANT_JOB_AVAILABILITY_CRON} set in messagingapis.
 *
 * <p>Note for whoever surfaces these in admin: the availability timeline renders from an explicit
 * allow-list ({@code SPController.USER_AVAILABILITY_HISTORY_ACTIONS}), so these rows are written
 * but not displayed until both strings are added to {@code SpUpdateFieldsEnum} and to that list.
 * Rows written beforehand are picked up retroactively.
 */
public final class SpKitApprovalActions {

  /**
   * Written by the 20:00 job when it pushes the heads-up to an SP. Records that a notification was
   * sent, not that anything changed — the availability flip comes later, under {@link
   * #AVAILABILITY_TURNED_ON}.
   *
   * <p>Doubles as this job's idempotency marker: the eligibility query excludes SPs that already
   * have one of these rows inside the window's day, which is what stops a retry re-pushing.
   */
  public static final String KIT_APPROVED_NOTIFIED = "USER_AVAILABILITY_ON_NOTIFIED";

  /** Written by the 23:30 job when it actually turns an SP's availability on. */
  public static final String AVAILABILITY_TURNED_ON = "USER_AVAILABILITY_ON_BY_CRON";

  private SpKitApprovalActions() {}
}
