package com.yesmadam.schedulerservice.jobs.autoassign.enums;

import static com.yesmadam.schedulerservice.utils.SettingConfigUtil.intConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.yesmadam.persistence.SettingsForEnalbeDisable;

/**
 * Auto-assign gates for the same-day (D0) run. Each constant binds a {@code tbl_settings} key to
 * the default applied when that row is missing, blank, or unparseable, and can resolve its own
 * value from a settings map.
 *
 * <p>The defaults are the values the cron currently hardcodes in {@code JobDistributionConstants}.
 * Moving them into {@code tbl_settings} is the one behavioural difference from the cron: ops can
 * retune the bar without a deploy, and an absent settings row keeps today's behaviour exactly.
 */
public enum AutoAssignConfig {

  /**
   * Reassign attempts after which an unassigned order stops being auto-assigned and is queued for
   * distribution instead. Auto-assign keeps the order while {@code reassignCount < threshold}.
   */
  REASSIGN_THRESHOLD("AUTO_ASSIGN_REASSIGN_THRESHOLD", 2),

  /**
   * Minutes-to-start below which an order is queued for distribution regardless of reassign count —
   * too close to its slot for another auto-assign round to land.
   */
  PRIORITY_TIME_THRESHOLD_MINUTES("AUTO_ASSIGN_PRIORITY_TIME_THRESHOLD_MINUTES", 60),

  /**
   * Minimum runway, in minutes, a non-express order must still have before it is picked up at all.
   * Applied in the candidate query as {@code order_start_time_with_date >= now + this}.
   *
   * <p>Cron's own same-day floor has since been reduced to zero (no more {@code now().plusHours(1)}
   * ), so 60 is now higher than cron's current behaviour, not a mirror of it — left unchanged here
   * since only the express carve-out below was in scope for that follow-up. An express-flagged
   * order bypasses this floor entirely regardless; see {@link #EXPRESS_MIN_MINUTES_BEFORE_START}.
   */
  MIN_LEAD_TIME_MINUTES("AUTO_ASSIGN_MIN_LEAD_TIME_MINUTES", 60),

  /**
   * Minutes-to-start below which a fresh, express-flagged order may still be pushed through
   * auto-assign as a last resort, bypassing the reassign-count and accepted-then-cancelled checks
   * entirely. Only applied for {@link com.yesmadam.schedulerservice.jobs.autoassign.enums
   * .AutoAssignScope#SAME_DAY}.
   *
   * <p>Ported from cron's {@code JobDistributionConstants.MIN_MINUTES_BEFORE_START} (currently also
   * 45), but kept as its own settings-backed key rather than reused directly: that constant is the
   * distribution engine's own pick-floor for a different decision — whether a fresh distribution
   * round can still target the order — which happens to share a value today but is owned separately
   * and could drift.
   */
  EXPRESS_MIN_MINUTES_BEFORE_START("AUTO_ASSIGN_EXPRESS_MIN_MINUTES_BEFORE_START", 45),

  /**
   * How recently, in minutes, an express order must have been created for the last-resort bypass
   * above to apply — restricts it to an order just placed and already close to its slot, not any
   * express order that happens to be running late. Ported from cron's {@code
   * JobDistributionConstants.EXPRESS_ORDER_AGE_LOOKBACK_MINUTES}.
   */
  EXPRESS_ORDER_AGE_LOOKBACK_MINUTES("AUTO_ASSIGN_EXPRESS_ORDER_AGE_LOOKBACK_MINUTES", 15);

  private final String key;
  private final int defaultValue;

  AutoAssignConfig(String key, int defaultValue) {
    this.key = key;
    this.defaultValue = defaultValue;
  }

  public String key() {
    return key;
  }

  /** Resolves this gate as an int, falling back to its default. */
  public int intValue(Map<String, SettingsForEnalbeDisable> cfg) {
    return intConfig(cfg, key, defaultValue);
  }

  /** All settings keys — for a single bulk fetch. */
  public static List<String> keys() {
    return Arrays.stream(values()).map(AutoAssignConfig::key).collect(Collectors.toList());
  }
}
