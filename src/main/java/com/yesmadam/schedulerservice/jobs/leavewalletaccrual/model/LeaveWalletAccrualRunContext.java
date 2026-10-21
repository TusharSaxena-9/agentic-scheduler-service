package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the workflow needs to drive one accrual run: every SP to update, the subset that gets
 * the SDL-restricted grant, and the settings values for this run. Fetched once by a read-only
 * activity so eligibility and settings stay stable across all chunks, and the values are recorded
 * in workflow history so retries/replays see identical inputs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveWalletAccrualRunContext {

  /** Every SP id present in tbl_sp_leave_wallet, chunked by the workflow for the write phase. */
  private List<Integer> spIds;

  /** Subset of spIds whose hub+category has SDL enabled — gets the reduced monthly grant. */
  private List<Integer> spaSpIds;

  private BigDecimal standardWoMonthly;
  private BigDecimal spaHydraSdlMonthly;
}
