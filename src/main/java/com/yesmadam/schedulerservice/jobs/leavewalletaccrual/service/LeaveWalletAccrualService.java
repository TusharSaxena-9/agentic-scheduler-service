package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.LeaveWalletAccrualRunContext;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.repository.SpLeaveWalletRepository;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

/**
 * Accrues monthly leave into {@code tbl_sp_leave_wallet}. SPs get a reduced grant ({@code
 * standard_wo_monthly - spa_hydra_sdl_monthly}) when SDL is toggled on for their hub+category
 * combination in {@code tbl_qr_payment_hub_category_mapping}; everyone else gets the full {@code
 * standard_wo_monthly} grant.
 *
 * <p>Split into a read-only context fetch and a chunked write so the write transaction never joins
 * {@code tbl_sp}/{@code tbl_sp_working_categories}/{@code tbl_qr_payment_hub_category_mapping} —
 * busy tables shared with other apps — and each write only locks a bounded batch of {@code
 * tbl_sp_leave_wallet} rows instead of the whole table at once.
 */
@Slf4j
@Service
public class LeaveWalletAccrualService {

  private static final String STANDARD_WO_MONTHLY_KEY = "standard_wo_monthly";
  private static final String SPA_HYDRA_SDL_MONTHLY_KEY = "spa_hydra_sdl_monthly";

  /** Placeholder id so an empty spaSpIds list never produces an invalid SQL `IN ()` clause. */
  private static final List<Integer> NO_SPA_SP_IDS = List.of(-1);

  private final SettingService settingService;
  private final SpLeaveWalletRepository spLeaveWalletRepository;

  public LeaveWalletAccrualService(
      SettingService settingService, SpLeaveWalletRepository spLeaveWalletRepository) {
    this.settingService = settingService;
    this.spLeaveWalletRepository = spLeaveWalletRepository;
  }

  @Transactional(readOnly = true)
  public LeaveWalletAccrualRunContext fetchRunContext() {
    Map<String, SettingsForEnalbeDisable> settings =
        settingService.findSettingWithKeys(
            List.of(STANDARD_WO_MONTHLY_KEY, SPA_HYDRA_SDL_MONTHLY_KEY));
    BigDecimal standardWoMonthly = settingValue(settings, STANDARD_WO_MONTHLY_KEY);
    BigDecimal spaHydraSdlMonthly = settingValue(settings, SPA_HYDRA_SDL_MONTHLY_KEY);

    QueryTimer timer = new QueryTimer();
    List<Integer> spIds = timer.time("findAllSpIds", spLeaveWalletRepository::findAllSpIds);
    List<Integer> spaSpIds =
        timer.time("findSdlRestrictedSpIds", spLeaveWalletRepository::findSdlRestrictedSpIds);

    log.info(
        "event=leave_wallet_accrual_context_fetched sp_count={} spa_sp_count={} durations_ms={} row_counts={}",
        spIds.size(),
        spaSpIds.size(),
        timer.getDurationsMs(),
        timer.getRowCounts());

    return new LeaveWalletAccrualRunContext(
        spIds, spaSpIds, standardWoMonthly, spaHydraSdlMonthly);
  }

  @Transactional
  public int accrueMonthlyLeaveChunk(
      List<Integer> chunkSpIds,
      List<Integer> spaSpIds,
      BigDecimal standardWoMonthly,
      BigDecimal spaHydraSdlMonthly) {
    if (chunkSpIds.isEmpty()) {
      return 0;
    }
    List<Integer> spaSpIdsParam = spaSpIds.isEmpty() ? NO_SPA_SP_IDS : spaSpIds;

    long start = System.currentTimeMillis();
    int rowsUpdated =
        spLeaveWalletRepository.accrueMonthlyLeaveChunk(
            chunkSpIds, spaSpIdsParam, standardWoMonthly, spaHydraSdlMonthly);
    long durationMs = System.currentTimeMillis() - start;

    log.info(
        "event=leave_wallet_accrual_chunk_updated chunk_size={} rows_updated={} duration_ms={}",
        chunkSpIds.size(),
        rowsUpdated,
        durationMs);

    return rowsUpdated;
  }

  private BigDecimal settingValue(Map<String, SettingsForEnalbeDisable> settings, String key) {
    SettingsForEnalbeDisable setting = settings.get(key);
    if (setting == null) {
      throw new IllegalStateException("Missing tbl_settings entry for key: " + key);
    }
    return new BigDecimal(setting.getValue());
  }
}
