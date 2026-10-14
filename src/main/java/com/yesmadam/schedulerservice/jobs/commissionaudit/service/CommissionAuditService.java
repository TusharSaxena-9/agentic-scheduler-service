package com.yesmadam.schedulerservice.jobs.commissionaudit.service;

import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditReport;

/** Business logic for the daily commission deduction audit. */
public interface CommissionAuditService {

  /**
   * Audits yesterday's (Asia/Kolkata) completed orders for missing commission deductions.
   *
   * @return the report. {@code rows} is empty when every order is clean
   */
  CommissionAuditReport fetchReport();

  /**
   * Mails the report to the configured recipients. A clean report sends nothing.
   *
   * @param report the findings to send
   */
  void mailReport(CommissionAuditReport report);
}
