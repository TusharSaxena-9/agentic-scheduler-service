package com.yesmadam.schedulerservice.jobs.commissionaudit.activity;

import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditReport;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activities for the commission deduction audit.
 *
 * <p>Fetch and mail are separate activities, so an SES outage retries only the mail and the audit
 * query does not run again.
 */
@ActivityInterface
public interface CommissionAuditActivities {

  /**
   * Audits yesterday's completed orders.
   *
   * @return the findings. {@code rows} is empty when every order is clean
   */
  @ActivityMethod
  CommissionAuditReport fetchCommissionAuditReport();

  /**
   * Mails the findings. A clean report sends nothing.
   *
   * @param report the findings returned by {@link #fetchCommissionAuditReport()}
   */
  @ActivityMethod
  void mailCommissionAuditReport(CommissionAuditReport report);
}
