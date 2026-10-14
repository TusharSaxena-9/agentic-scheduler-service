package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.enums.ActivityScoreDecayConfig;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayChunkResult;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayMultiplierCurve;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.repository.UserActivityScoreDecayDao;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;

@ExtendWith(MockitoExtension.class)
class UserActivityScoreDecayServiceImplTest {

  @Mock UserActivityScoreDecayDao dao;
  @Mock SettingService settingService;
  @InjectMocks UserActivityScoreDecayServiceImpl service;

  @Test
  void fetchMultiplierCurve_shouldFallBackToDefaults_whenNoSettingsRowsExist() {
    when(settingService.findSettingWithKeys(ActivityScoreDecayConfig.keys()))
        .thenReturn(Collections.emptyMap());

    DecayMultiplierCurve curve = service.fetchMultiplierCurve();

    assertThat(curve.getD0()).isEqualTo(1.0);
    assertThat(curve.getD1()).isEqualTo(0.9);
    assertThat(curve.getD2()).isEqualTo(0.8);
    assertThat(curve.getD3()).isEqualTo(0.7);
    assertThat(curve.getD4()).isEqualTo(0.6);
    assertThat(curve.getD5()).isEqualTo(0.5);
    assertThat(curve.getD6()).isEqualTo(0.4);
    assertThat(curve.getD7()).isEqualTo(0.3);
    assertThat(curve.getD8()).isEqualTo(0.2);
    assertThat(curve.getD8Plus()).isEqualTo(0.1);
  }

  @Test
  void fetchMultiplierCurve_shouldUseConfiguredValue_whenSettingsRowOverridesOneDay() {
    SettingsForEnalbeDisable d1Override = new SettingsForEnalbeDisable();
    d1Override.setValue("0.65");
    Map<String, SettingsForEnalbeDisable> cfg = Map.of(ActivityScoreDecayConfig.D1.key(), d1Override);
    when(settingService.findSettingWithKeys(ActivityScoreDecayConfig.keys())).thenReturn(cfg);

    DecayMultiplierCurve curve = service.fetchMultiplierCurve();

    assertThat(curve.getD1()).isEqualTo(0.65);
    assertThat(curve.getD0()).isEqualTo(1.0);
  }

  private static final DecayMultiplierCurve CURVE =
      new DecayMultiplierCurve(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1);

  @Test
  void decayDueBatch_shouldDelegateToDaoWithCurveAndBatchSize_andReturnRowsUpdated() {
    when(dao.decayDueBatch(
            CURVE.getD0(), CURVE.getD1(), CURVE.getD2(), CURVE.getD3(), CURVE.getD4(),
            CURVE.getD5(), CURVE.getD6(), CURVE.getD7(), CURVE.getD8(), CURVE.getD8Plus(), 10_000))
        .thenReturn(137);

    DecayChunkResult result = service.decayDueBatch(CURVE, 10_000);

    assertThat(result.getRowsUpdated()).isEqualTo(137);
    verify(dao)
        .decayDueBatch(
            CURVE.getD0(), CURVE.getD1(), CURVE.getD2(), CURVE.getD3(), CURVE.getD4(),
            CURVE.getD5(), CURVE.getD6(), CURVE.getD7(), CURVE.getD8(), CURVE.getD8Plus(), 10_000);
  }

  @Test
  void decayDueBatch_shouldThreadQueryTimerDiagnosticsIntoResult() {
    when(dao.decayDueBatch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(50);

    DecayChunkResult result = service.decayDueBatch(CURVE, 10_000);

    assertThat(result.getQueryDurationsMs()).containsKey("decayDueBatch");
    assertThat(result.getQueryRowCounts()).containsEntry("decayDueBatch", 50);
  }

  @Test
  void decayDueBatch_shouldReturnZeroRowsUpdated_whenNothingIsDue() {
    when(dao.decayDueBatch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
        .thenReturn(0);

    DecayChunkResult result = service.decayDueBatch(CURVE, 10_000);

    assertThat(result.getRowsUpdated()).isZero();
  }

  @Test
  void creditExpiredSuppressions_shouldDelegateToDaoWithBatchSize_andReturnRowsUpdated() {
    when(dao.creditExpiredSuppressions(10_000)).thenReturn(4);

    DecayChunkResult result = service.creditExpiredSuppressions(10_000);

    assertThat(result.getRowsUpdated()).isEqualTo(4);
    verify(dao).creditExpiredSuppressions(10_000);
  }

  @Test
  void creditExpiredSuppressions_shouldThreadQueryTimerDiagnosticsIntoResult() {
    when(dao.creditExpiredSuppressions(10_000)).thenReturn(4);

    DecayChunkResult result = service.creditExpiredSuppressions(10_000);

    assertThat(result.getQueryDurationsMs()).containsKey("creditExpiredSuppressions");
    assertThat(result.getQueryRowCounts()).containsEntry("creditExpiredSuppressions", 4);
  }

  @Test
  void creditExpiredSuppressions_shouldReturnZeroRowsUpdated_whenNothingExpiresToday() {
    when(dao.creditExpiredSuppressions(10_000)).thenReturn(0);

    DecayChunkResult result = service.creditExpiredSuppressions(10_000);

    assertThat(result.getRowsUpdated()).isZero();
  }
}
