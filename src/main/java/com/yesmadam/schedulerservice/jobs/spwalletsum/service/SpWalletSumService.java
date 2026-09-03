package com.yesmadam.schedulerservice.jobs.spwalletsum.service;

import com.yesmadam.schedulerservice.jobs.spwalletsum.model.SpWalletSumReport;

/** Business logic for the daily SP wallet sum snapshot. */
public interface SpWalletSumService {

  /** Sums every SP's main and saving wallet balance, bucketed by active/deactive status. */
  SpWalletSumReport computeWalletSum();

  /**
   * Persists the sums as a new {@code tbl_daily_sp_balance} row.
   *
   * @param report the sums to snapshot
   */
  void saveDailyBalance(SpWalletSumReport report);
}
