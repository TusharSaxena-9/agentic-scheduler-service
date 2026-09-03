package com.yesmadam.schedulerservice.jobs.spwalletsum.activity;

import com.yesmadam.schedulerservice.jobs.spwalletsum.model.SpWalletSumReport;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface SpWalletSumActivities {

  /** Sums every SP's main and saving wallet, bucketed by active/deactive status. */
  @ActivityMethod
  SpWalletSumReport computeWalletSum();

  /** Persists the sums as a new row in {@code tbl_daily_sp_balance}. */
  @ActivityMethod
  void saveDailyBalance(SpWalletSumReport report);
}
