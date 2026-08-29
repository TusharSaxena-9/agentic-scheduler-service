package com.yesmadam.schedulerservice.jobs.passbookmismatch.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.model.PassbookMismatchReport;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.service.PassbookMismatchService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter between Temporal and the service layer — all logic lives in {@link
 * PassbookMismatchService}.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class PassbookMismatchActivitiesImpl implements PassbookMismatchActivities {

  private final PassbookMismatchService passbookMismatchService;

  public PassbookMismatchActivitiesImpl(PassbookMismatchService passbookMismatchService) {
    this.passbookMismatchService = passbookMismatchService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "fetch-passbook-mismatch-report")
  @HandleExceptions
  public PassbookMismatchReport fetchPassbookMismatchReport() {
    return passbookMismatchService.fetchReport();
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "mail-passbook-mismatch-report")
  @HandleExceptions
  public void mailPassbookMismatchReport(PassbookMismatchReport report) {
    passbookMismatchService.mailReport(report);
  }
}
