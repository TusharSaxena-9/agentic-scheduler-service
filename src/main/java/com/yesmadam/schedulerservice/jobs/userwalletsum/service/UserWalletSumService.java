package com.yesmadam.schedulerservice.jobs.userwalletsum.service;

import com.yesmadam.schedulerservice.jobs.userwalletsum.model.UserWalletSumReport;

/** Business logic for the daily user wallet sum snapshot. */
public interface UserWalletSumService {

  /** Sums every user's main and seamless wallet balance. */
  UserWalletSumReport computeWalletSum();

  /**
   * Persists the sums as a new {@code tbl_daily_customer_balance} row.
   *
   * @param report the sums to snapshot
   */
  void saveDailyBalance(UserWalletSumReport report);
}
