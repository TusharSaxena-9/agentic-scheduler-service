package com.yesmadam.schedulerservice.jobs.producthublivestock.service;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.producthublivestock.model.LiveStockAlertReport;

/** Business logic for the product-hub live-stock alert. */
public interface ProductHubLiveStockAlertService {

  /**
   * Checks every configured hub against every configured product and returns whatever is at or
   * below zero live stock right now.
   *
   * @return the report; {@code rows} is empty when nothing is configured or nothing is flagged
   */
  LiveStockAlertReport fetchReport();

  /**
   * Mails the report to the configured recipients. A clean report sends nothing.
   *
   * @param report the findings to send
   */
  void mailReport(LiveStockAlertReport report);

  /**
   * Sends the report by WhatsApp to the configured numbers, using the given template. A clean
   * report sends nothing.
   *
   * @param report the findings to send
   * @param templateName the approved WhatsApp template name
   * @param whatsappNumbers recipient mobile numbers
   */
  void whatsappReport(LiveStockAlertReport report, String templateName, List<String> whatsappNumbers);
}
