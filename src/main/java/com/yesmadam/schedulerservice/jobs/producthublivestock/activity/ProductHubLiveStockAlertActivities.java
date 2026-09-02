package com.yesmadam.schedulerservice.jobs.producthublivestock.activity;

import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertReport;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activities for the product-hub live-stock alert.
 *
 * <p>Fetch, mail and WhatsApp are three separate activities on purpose: an outage in one channel
 * must not re-run the lookup query, and must not stop the other channel's send.
 */
@ActivityInterface
public interface ProductHubLiveStockAlertActivities {

  /**
   * Checks every configured hub &times; product combination right now.
   *
   * @return the findings; rows are empty when nothing is configured or nothing is flagged
   */
  @ActivityMethod
  LiveStockAlertReport fetchLiveStockAlertReport();

  /**
   * Mails the findings. A clean report sends nothing.
   *
   * @param report the findings returned by {@link #fetchLiveStockAlertReport()}
   */
  @ActivityMethod
  void mailLiveStockAlertReport(LiveStockAlertReport report);

  /**
   * Sends the findings by WhatsApp to the configured numbers. A clean report sends nothing.
   *
   * @param report the findings returned by {@link #fetchLiveStockAlertReport()}
   */
  @ActivityMethod
  void whatsappLiveStockAlertReport(LiveStockAlertReport report);
}
