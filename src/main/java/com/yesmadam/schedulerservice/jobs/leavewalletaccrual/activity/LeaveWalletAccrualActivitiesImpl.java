package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.activity;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.LeaveWalletAccrualRunContext;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.service.LeaveWalletAccrualService;

import io.temporal.spring.boot.ActivityImpl;

/**
 * Implementation of leave wallet accrual activities. Thin adapter between Temporal and {@link
 * LeaveWalletAccrualService}, which holds the actual business logic.
 */
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class LeaveWalletAccrualActivitiesImpl implements LeaveWalletAccrualActivities {

  private final LeaveWalletAccrualService leaveWalletAccrualService;

  public LeaveWalletAccrualActivitiesImpl(LeaveWalletAccrualService leaveWalletAccrualService) {
    this.leaveWalletAccrualService = leaveWalletAccrualService;
  }

  @Override
  @Loggable(operation = "fetch-leave-wallet-accrual-run-context")
  @HandleExceptions
  public LeaveWalletAccrualRunContext fetchLeaveWalletAccrualRunContext() {
    return leaveWalletAccrualService.fetchRunContext();
  }

  @Override
  @Loggable(operation = "accrue-monthly-leave-chunk")
  @HandleExceptions
  public int accrueMonthlyLeaveChunk(
      List<Integer> chunkSpIds,
      List<Integer> spaSpIds,
      BigDecimal standardWoMonthly,
      BigDecimal spaHydraSdlMonthly) {
    return leaveWalletAccrualService.accrueMonthlyLeaveChunk(
        chunkSpIds, spaSpIds, standardWoMonthly, spaHydraSdlMonthly);
  }
}
