package com.yesmadam.schedulerservice.jobs.commissionaudit.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.commissionaudit.model.CommissionAuditReport;
import com.yesmadam.schedulerservice.jobs.commissionaudit.service.CommissionAuditService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter between Temporal and the service layer. All logic lives in {@link
 * CommissionAuditService}.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class CommissionAuditActivitiesImpl implements CommissionAuditActivities {

  private final CommissionAuditService commissionAuditService;

  public CommissionAuditActivitiesImpl(CommissionAuditService commissionAuditService) {
    this.commissionAuditService = commissionAuditService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "fetch-commission-audit-report")
  @HandleExceptions
  public CommissionAuditReport fetchCommissionAuditReport() {
    return commissionAuditService.fetchReport();
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "mail-commission-audit-report")
  @HandleExceptions
  public void mailCommissionAuditReport(CommissionAuditReport report) {
    commissionAuditService.mailReport(report);
  }
}
