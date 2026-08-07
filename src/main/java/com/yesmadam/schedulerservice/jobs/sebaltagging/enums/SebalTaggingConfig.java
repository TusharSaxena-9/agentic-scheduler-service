package com.yesmadam.schedulerservice.jobs.sebaltagging.enums;

import static com.yesmadam.schedulerservice.utils.SettingConfigUtil.stringConfig;

import java.util.Map;

import com.yesmadam.persistence.SettingsForEnalbeDisable;

import lombok.extern.slf4j.Slf4j;

/**
 * SEBAL tagging job settings, stored as {@code tbl_settings} key/value rows — same mechanism
 * {@code RoseOrderEligibilityConfig} uses for the eligibility thresholds, rather than a bespoke
 * column on this job's own tables.
 */
@Slf4j
public enum SebalTaggingConfig {

  /**
   * Manual run-mode override: set this row's value to {@code FULL} to rebuild the whole running
   * aggregate from the 2022-onward population instead of folding a delta onto the existing totals.
   *
   * <p><b>A one-shot trigger.</b> A FULL run resets this row to {@code INCREMENTAL} itself once it
   * finishes successfully, so the next scheduled run goes back to the normal nightly delta. That
   * makes "leaving it on FULL by accident" impossible — otherwise every nightly run would rebuild
   * 1.4M orders from scratch, which nothing needs and which would keep re-tiering the table the
   * assignment cascade reads.
   *
   * <p>The reset happens only after the run completes, so a FULL that fails partway stays FULL and
   * the next run retries it — the intent survives until it has actually been carried out.
   */
  RUN_MODE("SEBAL_TAGGING_RUN_MODE");

  private final String key;

  SebalTaggingConfig(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /**
   * Resolves the configured run mode, defaulting to {@link SebalTaggingRunMode#INCREMENTAL} if
   * the row is missing, blank, or holds a value that isn't {@code FULL}/{@code INCREMENTAL}.
   */
  public SebalTaggingRunMode resolve(Map<String, SettingsForEnalbeDisable> cfg) {
    String raw = stringConfig(cfg, key, SebalTaggingRunMode.INCREMENTAL.name());
    try {
      return SebalTaggingRunMode.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException e) {
      log.error(
          "Invalid run mode for config {} = '{}', using default {}",
          key,
          raw,
          SebalTaggingRunMode.INCREMENTAL);
      return SebalTaggingRunMode.INCREMENTAL;
    }
  }
}
