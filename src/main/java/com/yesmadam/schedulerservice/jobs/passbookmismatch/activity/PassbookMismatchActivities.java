package com.yesmadam.schedulerservice.jobs.passbookmismatch.activity;

import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchReport;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activities for the SP passbook mismatch audit.
 *
 * <p>Fetch and mail are separate activities on purpose: if SES is down, only the mail step retries
 * — the audit query does not run again.
 */
@ActivityInterface
public interface PassbookMismatchActivities {

  /**
   * Audits the trailing window.
   *
   * @return the findings; rows are empty when the window is clean
   */
  @ActivityMethod
  PassbookMismatchReport fetchPassbookMismatchReport();

  /**
   * Mails the findings. A clean report sends nothing.
   *
   * @param report the findings returned by {@link #fetchPassbookMismatchReport()}
   */
  @ActivityMethod
  void mailPassbookMismatchReport(PassbookMismatchReport report);
}
