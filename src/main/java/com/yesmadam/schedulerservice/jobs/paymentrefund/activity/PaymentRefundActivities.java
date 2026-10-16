package com.yesmadam.schedulerservice.jobs.paymentrefund.activity;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PaymentRefundRowResult;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentRefundActivities {

  /**
   * Candidate {@code tbl_checksum_request} row ids: {@code orderCreated=false}, {@code
   * payingFor="Service"}, {@code successAt} in the trailing 10-15 minute window ending at the time
   * this activity runs. The window is a fixed-length lookback, not a calendar bucket, so it needs
   * no explicit timezone (CLAUDE.md rule 1 applies to day-boundary windows; this isn't one).
   * Returns bounded ids only, never the rows themselves (rule 9).
   */
  @ActivityMethod
  List<Long> findPendingChecksumIds();

  /**
   * Re-verifies and resolves exactly one row. Safe to retry: it re-checks whether an order or
   * refund already exists for the row before doing anything (CLAUDE.md rule 14) — see the known-gap
   * note on {@link
   * com.yesmadam.schedulerservice.jobs.paymentrefund.service.PaymentRefundOrderCreationService} for
   * the one case that guard doesn't cover.
   */
  @ActivityMethod
  PaymentRefundRowResult processChecksumRow(Long checksumId);
}
