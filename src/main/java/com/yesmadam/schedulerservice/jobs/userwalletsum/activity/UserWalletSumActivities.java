package com.yesmadam.schedulerservice.jobs.userwalletsum.activity;

import com.yesmadam.schedulerservice.jobs.userwalletsum.model.UserWalletSumReport;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface UserWalletSumActivities {

  /** Sums {@code walletMin} and {@code amountLimit} across every row in {@code tbl_user}. */
  @ActivityMethod
  UserWalletSumReport computeWalletSum();

  /** Persists the sums as a new row in {@code tbl_daily_customer_balance}. */
  @ActivityMethod
  void saveDailyBalance(UserWalletSumReport report);
}
