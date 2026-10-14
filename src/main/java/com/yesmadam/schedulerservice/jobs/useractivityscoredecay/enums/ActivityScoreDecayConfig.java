package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.enums;

import static com.yesmadam.schedulerservice.utils.SettingConfigUtil.decimalConfig;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.yesmadam.persistence.SettingsForEnalbeDisable;

public enum ActivityScoreDecayConfig {

  D0("ACTIVITY_SCORE_DECAY_D0_PCT", new BigDecimal("1.0")),
  D1("ACTIVITY_SCORE_DECAY_D1_PCT", new BigDecimal("0.9")),
  D2("ACTIVITY_SCORE_DECAY_D2_PCT", new BigDecimal("0.8")),
  D3("ACTIVITY_SCORE_DECAY_D3_PCT", new BigDecimal("0.7")),
  D4("ACTIVITY_SCORE_DECAY_D4_PCT", new BigDecimal("0.6")),
  D5("ACTIVITY_SCORE_DECAY_D5_PCT", new BigDecimal("0.5")),
  D6("ACTIVITY_SCORE_DECAY_D6_PCT", new BigDecimal("0.4")),
  D7("ACTIVITY_SCORE_DECAY_D7_PCT", new BigDecimal("0.3")),
  D8("ACTIVITY_SCORE_DECAY_D8_PCT", new BigDecimal("0.2")),
  D8_PLUS("ACTIVITY_SCORE_DECAY_D8_PLUS_PCT", new BigDecimal("0.1"));

  private final String key;
  private final BigDecimal defaultValue;

  ActivityScoreDecayConfig(String key, BigDecimal defaultValue) {
    this.key = key;
    this.defaultValue = defaultValue;
  }

  public String key() {
    return key;
  }

  public BigDecimal decimal(Map<String, SettingsForEnalbeDisable> cfg) {
    return decimalConfig(cfg, key, defaultValue);
  }

  public static List<String> keys() {
    return Arrays.stream(values())
        .map(ActivityScoreDecayConfig::key)
        .collect(Collectors.toList());
  }
}
