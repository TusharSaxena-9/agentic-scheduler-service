package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.activity;

import java.math.BigDecimal;
import java.util.List;

import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.LeaveWalletAccrualRunContext;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for the monthly SP leave wallet accrual.
 *
 * <p>The run is split into one read-only context fetch plus one activity call per chunk, so the
 * write phase never joins tbl_sp/tbl_sp_working_categories/tbl_qr_payment_hub_category_mapping —
 * those are resolved once up front — and each chunk's completion is recorded in workflow history: a
 * failure resumes from the failed chunk instead of re-running the whole wallet table.
 */
@ActivityInterface
public interface LeaveWalletAccrualActivities {

  /**
   * Loads every SP id to update, the SDL-restricted subset, and the settings values for this run.
   *
   * @return the run context the workflow iterates over
   */
  @ActivityMethod
  LeaveWalletAccrualRunContext fetchLeaveWalletAccrualRunContext();

  /**
   * Accrues one month of leave for one chunk of SPs. Atomic: the chunk commits or rolls back as one
   * transaction scoped to {@code chunkSpIds}.
   *
   * @return number of tbl_sp_leave_wallet rows updated in this chunk
   */
  @ActivityMethod
  int accrueMonthlyLeaveChunk(
      List<Integer> chunkSpIds,
      List<Integer> spaSpIds,
      BigDecimal standardWoMonthly,
      BigDecimal spaHydraSdlMonthly);
}
