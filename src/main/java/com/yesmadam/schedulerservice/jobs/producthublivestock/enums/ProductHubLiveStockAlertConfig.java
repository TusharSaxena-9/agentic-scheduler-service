package com.yesmadam.schedulerservice.jobs.producthublivestock.enums;

/**
 * {@code tbl_settings} keys for the product-hub live-stock alert job.
 *
 * <p>Both rows hold a comma-separated list of integer ids (e.g. {@code "12,45,7"}), read via {@link
 * com.yesmadam.schedulerservice.utils.SettingConfigUtil#intListConfig}. The job checks every
 * configured hub against every configured product (cross product) — an empty or missing row on
 * either side means "nothing to check", not "check everything".
 *
 * <p>These rows do not exist by default. Ops must insert them before the schedule in {@code
 * schedules.yml} is unpaused, or every run will find nothing configured and skip silently.
 */
public enum ProductHubLiveStockAlertConfig {
  HUB_IDS("PRODUCT_HUB_LIVE_STOCK_ALERT_HUB_IDS"),
  PRODUCT_IDS("PRODUCT_HUB_LIVE_STOCK_ALERT_PRODUCT_IDS");

  private final String key;

  ProductHubLiveStockAlertConfig(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
