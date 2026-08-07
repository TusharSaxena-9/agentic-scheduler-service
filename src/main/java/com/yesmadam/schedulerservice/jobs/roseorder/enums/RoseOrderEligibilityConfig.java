package com.yesmadam.schedulerservice.jobs.roseorder.enums;

import static com.yesmadam.schedulerservice.utils.SettingConfigUtil.decimalConfig;
import static com.yesmadam.schedulerservice.utils.SettingConfigUtil.intConfig;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.yesmadam.persistence.SettingsForEnalbeDisable;

/**
 * Rose Order SP-eligibility thresholds. Each constant binds a {@code tbl_settings} key to the
 * default applied when that row is missing, blank, or unparseable, and can resolve its own value
 * from a settings map.
 */
public enum RoseOrderEligibilityConfig {

  /** Param 1 — minimum assign-to-complete ratio over the last 30 days (percentage). */
  ASSIGN_TO_COMPLETE_RATIO("ROSE_ORDER_ASSIGN_TO_COMPLETE_RATIO", new BigDecimal("85")),

  /** Param 2 — minimum average rating over the most-recent rated jobs. */
  RATING_MIN("ROSE_ORDER_RATING_MIN", new BigDecimal("4.5")),

  /** Param 3 — minimum all-time completed orders. */
  MIN_COMPLETED_ORDER("ROSE_ORDER_MIN_COMPLETED_ORDER", new BigDecimal("100"));

  private final String key;
  private final BigDecimal defaultValue;

  RoseOrderEligibilityConfig(String key, BigDecimal defaultValue) {
    this.key = key;
    this.defaultValue = defaultValue;
  }

  public String key() {
    return key;
  }

  /** Resolves this threshold as a {@link BigDecimal}, falling back to its default. */
  public BigDecimal decimal(Map<String, SettingsForEnalbeDisable> cfg) {
    return decimalConfig(cfg, key, defaultValue);
  }

  /** Resolves this threshold as an int, falling back to its default. */
  public int intValue(Map<String, SettingsForEnalbeDisable> cfg) {
    return intConfig(cfg, key, defaultValue.intValue());
  }

  /** All settings keys — for a single bulk fetch. */
  public static List<String> keys() {
    return Arrays.stream(values())
        .map(RoseOrderEligibilityConfig::key)
        .collect(Collectors.toList());
  }
}
