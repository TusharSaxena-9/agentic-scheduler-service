package com.yesmadam.schedulerservice.jobs.spwalletsum.service;

import java.sql.Timestamp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.spwalletsum.model.DailySpBalance;
import com.yesmadam.schedulerservice.jobs.spwalletsum.model.SpWalletSumReport;
import com.yesmadam.schedulerservice.jobs.spwalletsum.projections.SpWalletActiveDeactiveProjection;
import com.yesmadam.schedulerservice.jobs.spwalletsum.repository.DailySpBalanceRepository;
import com.yesmadam.schedulerservice.jobs.spwalletsum.repository.SpMainWalletRepository;
import com.yesmadam.schedulerservice.jobs.spwalletsum.repository.SpSavingWalletRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Sums every SP's main and saving wallet balance, bucketed by active/deactive status, and
 * snapshots the totals once a day — port of {@code SpWalletSumCron#spWalletSum} from
 * messagingapis onto Temporal.
 *
 * <p>No idempotency key is needed for the write (CLAUDE.md rule 14): {@code tbl_daily_sp_balance}
 * is append-only, each row is just a timestamped point-in-time snapshot, and nothing reads it as a
 * running total. A duplicate row from a retry is an extra snapshot identifiable by its {@code
 * createdAt}, not a wrong number silently double-applied.
 */
@Slf4j
@Service
public class SpWalletSumServiceImpl implements SpWalletSumService {

  private final SpMainWalletRepository spMainWalletRepository;
  private final SpSavingWalletRepository spSavingWalletRepository;
  private final DailySpBalanceRepository dailySpBalanceRepository;

  public SpWalletSumServiceImpl(
      SpMainWalletRepository spMainWalletRepository,
      SpSavingWalletRepository spSavingWalletRepository,
      DailySpBalanceRepository dailySpBalanceRepository) {
    this.spMainWalletRepository = spMainWalletRepository;
    this.spSavingWalletRepository = spSavingWalletRepository;
    this.dailySpBalanceRepository = dailySpBalanceRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public SpWalletSumReport computeWalletSum() {
    long start = System.currentTimeMillis();
    SpWalletActiveDeactiveProjection mainSum = spMainWalletRepository.sumMainWalletBySpStatus();
    SpWalletActiveDeactiveProjection savingSum =
        spSavingWalletRepository.sumSavingWalletBySpStatus();
    long durationMs = System.currentTimeMillis() - start;

    SpWalletSumReport report =
        new SpWalletSumReport(
            nullToZero(mainSum.getActiveSum()),
            nullToZero(savingSum.getActiveSum()),
            nullToZero(mainSum.getDeactiveSum()),
            nullToZero(savingSum.getDeactiveSum()));

    log.info(
        "event=sp_wallet_sum_computed main_active={} saving_active={} main_deactive={}"
            + " saving_deactive={} duration_ms={}",
        report.getMainActiveWalletSum(),
        report.getSavingActiveWalletSum(),
        report.getMainDeActiveWalletSum(),
        report.getSavingDeActiveWalletSum(),
        durationMs);

    return report;
  }

  @Override
  @Transactional
  public void saveDailyBalance(SpWalletSumReport report) {
    DailySpBalance balance = new DailySpBalance();
    balance.setMainActiveWalletSum(report.getMainActiveWalletSum());
    balance.setSavingActiveWalletSum(report.getSavingActiveWalletSum());
    balance.setMainDeActiveWalletSum(report.getMainDeActiveWalletSum());
    balance.setSavingDeActiveWalletSum(report.getSavingDeActiveWalletSum());
    balance.setCreatedAt(new Timestamp(System.currentTimeMillis()));

    dailySpBalanceRepository.save(balance);

    log.info(
        "event=sp_wallet_sum_saved main_active={} saving_active={} main_deactive={}"
            + " saving_deactive={}",
        report.getMainActiveWalletSum(),
        report.getSavingActiveWalletSum(),
        report.getMainDeActiveWalletSum(),
        report.getSavingDeActiveWalletSum());
  }

  /** {@code SUM(...)} over an empty bucket is {@code null}, not zero — a balance snapshot wants 0. */
  private static Long nullToZero(Long value) {
    return value == null ? 0L : value;
  }
}
