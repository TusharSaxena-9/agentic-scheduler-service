package com.yesmadam.schedulerservice.jobs.passbookmismatch.service;

import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchReport;

/** Business logic for the SP passbook mismatch audit. */
public interface PassbookMismatchService {

  /**
   * Audits the trailing window and returns everything flagged in it.
   *
   * @return the report; {@code rows} is empty when the window is clean
   */
  PassbookMismatchReport fetchReport();

  /**
   * Mails the report to the configured recipients.
   *
   * @param report the findings to send
   */
  void mailReport(PassbookMismatchReport report);
}
