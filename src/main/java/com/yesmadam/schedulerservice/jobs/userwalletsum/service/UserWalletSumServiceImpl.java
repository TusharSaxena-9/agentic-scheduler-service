package com.yesmadam.schedulerservice.jobs.userwalletsum.service;

import java.sql.Timestamp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.userwalletsum.model.DailyCustomerBalance;
import com.yesmadam.schedulerservice.jobs.userwalletsum.model.UserWalletSumReport;
import com.yesmadam.schedulerservice.jobs.userwalletsum.projections.UserWalletSumProjection;
import com.yesmadam.schedulerservice.jobs.userwalletsum.repository.DailyCustomerBalanceRepository;
import com.yesmadam.schedulerservice.jobs.userwalletsum.repository.UserWalletSumRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Sums every user's wallet balance and snapshots the total once a day, same as the messagingapis
 * original {@code UserWalletSumCron} — just ported onto a Temporal workflow.
 *
 * <p>No idempotency key is needed for the write (CLAUDE.md rule 14): {@code
 * tbl_daily_customer_balance} is append-only, each row is just a timestamped point-in-time
 * snapshot, and nothing reads it as a running total. A duplicate row from a retry is an extra
 * snapshot identifiable by its {@code createdAt}, not a wrong number silently double-applied.
 */
@Slf4j
@Service
public class UserWalletSumServiceImpl implements UserWalletSumService {

  private final UserWalletSumRepository userWalletSumRepository;
  private final DailyCustomerBalanceRepository dailyCustomerBalanceRepository;

  public UserWalletSumServiceImpl(
      UserWalletSumRepository userWalletSumRepository,
      DailyCustomerBalanceRepository dailyCustomerBalanceRepository) {
    this.userWalletSumRepository = userWalletSumRepository;
    this.dailyCustomerBalanceRepository = dailyCustomerBalanceRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public UserWalletSumReport computeWalletSum() {
    long start = System.currentTimeMillis();
    UserWalletSumProjection sum = userWalletSumRepository.sumAllWallets();
    long durationMs = System.currentTimeMillis() - start;

    UserWalletSumReport report =
        new UserWalletSumReport(
            sum == null ? null : sum.getMainWalletSum(),
            sum == null ? null : sum.getSeemlessWalletSum());

    log.info(
        "event=user_wallet_sum_computed main_wallet_sum={} seemless_wallet_sum={} duration_ms={}",
        report.getMainWalletSum(),
        report.getSeemlessWalletSum(),
        durationMs);

    return report;
  }

  @Override
  @Transactional
  public void saveDailyBalance(UserWalletSumReport report) {
    DailyCustomerBalance balance = new DailyCustomerBalance();
    balance.setMainWalletSum(report.getMainWalletSum());
    balance.setSeemlessWalletSum(report.getSeemlessWalletSum());
    balance.setCreatedAt(new Timestamp(System.currentTimeMillis()));

    dailyCustomerBalanceRepository.save(balance);

    log.info(
        "event=user_wallet_sum_saved main_wallet_sum={} seemless_wallet_sum={}",
        report.getMainWalletSum(),
        report.getSeemlessWalletSum());
  }
}
