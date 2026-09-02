package com.yesmadam.schedulerservice.jobs.userwalletsum.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.userwalletsum.model.UserWalletSumReport;
import com.yesmadam.schedulerservice.jobs.userwalletsum.service.UserWalletSumService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class UserWalletSumActivitiesImpl implements UserWalletSumActivities {

  private final UserWalletSumService userWalletSumService;

  public UserWalletSumActivitiesImpl(UserWalletSumService userWalletSumService) {
    this.userWalletSumService = userWalletSumService;
  }

  @Override
  @Loggable(operation = "compute-wallet-sum")
  @HandleExceptions
  public UserWalletSumReport computeWalletSum() {
    return userWalletSumService.computeWalletSum();
  }

  @Override
  @Loggable(operation = "save-daily-balance")
  @HandleExceptions
  public void saveDailyBalance(UserWalletSumReport report) {
    userWalletSumService.saveDailyBalance(report);
  }
}
