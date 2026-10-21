package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.LeaveWalletAccrualRunContext;
import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.repository.SpLeaveWalletRepository;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;

@ExtendWith(MockitoExtension.class)
class LeaveWalletAccrualServiceTest {

  @Mock SettingService settingService;
  @Mock SpLeaveWalletRepository spLeaveWalletRepository;
  @InjectMocks LeaveWalletAccrualService leaveWalletAccrualService;

  // ================================================================
  // fetchRunContext
  // ================================================================

  @Test
  void fetchRunContext_shouldAssembleContextFromRepositoryAndSettings() {
    when(settingService.findSettingWithKeys(
            List.of("standard_wo_monthly", "spa_hydra_sdl_monthly")))
        .thenReturn(
            Map.of(
                "standard_wo_monthly", buildSetting("standard_wo_monthly", "6"),
                "spa_hydra_sdl_monthly", buildSetting("spa_hydra_sdl_monthly", "1")));
    when(spLeaveWalletRepository.findAllSpIds()).thenReturn(List.of(1, 2, 3));
    when(spLeaveWalletRepository.findSdlRestrictedSpIds()).thenReturn(List.of(2));

    LeaveWalletAccrualRunContext context = leaveWalletAccrualService.fetchRunContext();

    assertThat(context.getSpIds()).containsExactly(1, 2, 3);
    assertThat(context.getSpaSpIds()).containsExactly(2);
    assertThat(context.getStandardWoMonthly()).isEqualTo(new BigDecimal("6"));
    assertThat(context.getSpaHydraSdlMonthly()).isEqualTo(new BigDecimal("1"));
  }

  @Test
  void fetchRunContext_shouldThrowIfStandardWoMonthlyMissing() {
    when(settingService.findSettingWithKeys(any()))
        .thenReturn(Map.of("spa_hydra_sdl_monthly", buildSetting("spa_hydra_sdl_monthly", "1")));

    assertThatThrownBy(() -> leaveWalletAccrualService.fetchRunContext())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("standard_wo_monthly");
    verifyNoInteractions(spLeaveWalletRepository);
  }

  @Test
  void fetchRunContext_shouldThrowIfSpaHydraSdlMonthlyMissing() {
    when(settingService.findSettingWithKeys(any()))
        .thenReturn(Map.of("standard_wo_monthly", buildSetting("standard_wo_monthly", "6")));

    assertThatThrownBy(() -> leaveWalletAccrualService.fetchRunContext())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spa_hydra_sdl_monthly");
    verifyNoInteractions(spLeaveWalletRepository);
  }

  // ================================================================
  // accrueMonthlyLeaveChunk
  // ================================================================

  @Test
  void accrueMonthlyLeaveChunk_shouldDelegateToRepositoryWithGivenSpaSpIds() {
    when(spLeaveWalletRepository.accrueMonthlyLeaveChunk(
            List.of(1, 2), List.of(2), new BigDecimal("6"), new BigDecimal("1")))
        .thenReturn(2);

    int rowsUpdated =
        leaveWalletAccrualService.accrueMonthlyLeaveChunk(
            List.of(1, 2), List.of(2), new BigDecimal("6"), new BigDecimal("1"));

    assertThat(rowsUpdated).isEqualTo(2);
  }

  @Test
  void accrueMonthlyLeaveChunk_shouldUsePlaceholderIdWhenNoSpaSpIds() {
    when(spLeaveWalletRepository.accrueMonthlyLeaveChunk(
            List.of(1), List.of(-1), new BigDecimal("6"), new BigDecimal("1")))
        .thenReturn(1);

    int rowsUpdated =
        leaveWalletAccrualService.accrueMonthlyLeaveChunk(
            List.of(1), Collections.emptyList(), new BigDecimal("6"), new BigDecimal("1"));

    assertThat(rowsUpdated).isEqualTo(1);
    verify(spLeaveWalletRepository)
        .accrueMonthlyLeaveChunk(List.of(1), List.of(-1), new BigDecimal("6"), new BigDecimal("1"));
  }

  @Test
  void accrueMonthlyLeaveChunk_shouldSkipIfChunkEmpty() {
    int rowsUpdated =
        leaveWalletAccrualService.accrueMonthlyLeaveChunk(
            Collections.emptyList(), List.of(2), new BigDecimal("6"), new BigDecimal("1"));

    assertThat(rowsUpdated).isEqualTo(0);
    verifyNoInteractions(spLeaveWalletRepository);
  }

  private SettingsForEnalbeDisable buildSetting(String key, String value) {
    SettingsForEnalbeDisable setting = new SettingsForEnalbeDisable();
    setting.setKey(key);
    setting.setValue(value);
    return setting;
  }
}
