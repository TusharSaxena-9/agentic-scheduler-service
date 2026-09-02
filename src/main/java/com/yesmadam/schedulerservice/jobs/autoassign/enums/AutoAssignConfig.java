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
   * Minimum runway, in minutes, an order must still have before it is picked up at all. Applied in
   * the candidate query as {@code order_start_time_with_date >= now + this}, mirroring the cron
   * DAO's {@code now().plusHours(1)} filter for same-day bookings.
   */
  MIN_LEAD_TIME_MINUTES("AUTO_ASSIGN_MIN_LEAD_TIME_MINUTES", 60);

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
