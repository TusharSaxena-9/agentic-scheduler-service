package com.yesmadam.schedulerservice.utils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yesmadam.persistence.SettingsForEnalbeDisable;

import lombok.extern.slf4j.Slf4j;

/**
 * Helpers for reading typed values out of {@code tbl_settings} config rows (keyed by config name),
 * falling back to a supplied default when the row is missing, blank, or unparseable.
 */
@Slf4j
public final class SettingConfigUtil {

  private SettingConfigUtil() {}

  /**
   * Reads an int config value, or returns {@code defaultValue} if the key is absent/blank/invalid.
   */
  public static int intConfig(
      Map<String, SettingsForEnalbeDisable> cfg, String key, int defaultValue) {
    SettingsForEnalbeDisable setting = cfg.get(key);
    if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(setting.getValue().trim());
    } catch (NumberFormatException e) {
      log.error(
          "Invalid int for config {} = '{}', using default {}",
          key,
          setting.getValue(),
          defaultValue);
      return defaultValue;
    }
  }

  /**
   * Reads a BigDecimal config value, or returns {@code defaultValue} if the key is
   * absent/blank/invalid.
   */
  public static BigDecimal decimalConfig(
      Map<String, SettingsForEnalbeDisable> cfg, String key, BigDecimal defaultValue) {
    SettingsForEnalbeDisable setting = cfg.get(key);
    if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
      return defaultValue;
    }
    try {
      return new BigDecimal(setting.getValue().trim());
    } catch (NumberFormatException e) {
      log.error(
          "Invalid decimal for config {} = '{}', using default {}",
          key,
          setting.getValue(),
          defaultValue);
      return defaultValue;
    }
  }

  /**
   * Reads a raw string config value, or returns {@code defaultValue} if the key is absent/blank.
   */
  public static String stringConfig(
      Map<String, SettingsForEnalbeDisable> cfg, String key, String defaultValue) {
    SettingsForEnalbeDisable setting = cfg.get(key);
    if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) {
      return defaultValue;
    }
    return setting.getValue().trim();
  }

  /**
   * Reads a comma-separated list of ints, e.g. {@code "12, 45,7"}. Missing/blank rows and blank
   * tokens (a stray trailing comma) return an empty list rather than a default — callers treat "not
   * configured" the same as "configured empty". A non-numeric token is logged and dropped rather
   * than failing the whole list, so one bad entry doesn't take out every id in the row.
   */
  public static List<Integer> intListConfig(Map<String, SettingsForEnalbeDisable> cfg, String key) {
    String raw = stringConfig(cfg, key, null);
    if (raw == null) {
      return List.of();
    }
    List<Integer> ids = new ArrayList<>();
    for (String token : raw.split(",")) {
      String trimmed = token.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        ids.add(Integer.parseInt(trimmed));
      } catch (NumberFormatException e) {
        log.error("Invalid id '{}' in config {} = '{}', dropping it", trimmed, key, raw);
      }
    }
    return ids;
  }
}
