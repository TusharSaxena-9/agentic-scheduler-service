package com.yesmadam.schedulerservice.jobs.spwalletsum.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.spwalletsum.model.SpWalletSumReport;
import com.yesmadam.schedulerservice.jobs.spwalletsum.service.SpWalletSumService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpWalletSumActivitiesImpl implements SpWalletSumActivities {

  private final SpWalletSumService spWalletSumService;

  public SpWalletSumActivitiesImpl(SpWalletSumService spWalletSumService) {
    this.spWalletSumService = spWalletSumService;
  }

  @Override
  @Loggable(operation = "compute-sp-wallet-sum")
  @HandleExceptions
  public SpWalletSumReport computeWalletSum() {
    return spWalletSumService.computeWalletSum();
  }

  @Override
  @Loggable(operation = "save-sp-daily-balance")
  @HandleExceptions
  public void saveDailyBalance(SpWalletSumReport report) {
    spWalletSumService.saveDailyBalance(report);
  }
}
