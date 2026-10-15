package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.enums.ActivityScoreDecayConfig;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayChunkResult;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayMultiplierCurve;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.repository.UserActivityScoreDecayDao;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;
import com.yesmadam.schedulerservice.utils.QueryTimer;

@Service
public class UserActivityScoreDecayServiceImpl implements UserActivityScoreDecayService {

  private final UserActivityScoreDecayDao dao;
  private final SettingService settingService;

  public UserActivityScoreDecayServiceImpl(
      UserActivityScoreDecayDao dao, SettingService settingService) {
    this.dao = dao;
    this.settingService = settingService;
  }

  @Override
  public DecayMultiplierCurve fetchMultiplierCurve() {
    Map<String, SettingsForEnalbeDisable> cfg =
        settingService.findSettingWithKeys(ActivityScoreDecayConfig.keys());
    return new DecayMultiplierCurve(
        ActivityScoreDecayConfig.D0.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D1.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D2.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D3.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D4.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D5.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D6.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D7.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D8.decimal(cfg).doubleValue(),
        ActivityScoreDecayConfig.D8_PLUS.decimal(cfg).doubleValue());
  }

  @Override
  public DecayChunkResult decayDueBatch(DecayMultiplierCurve curve, int batchSize) {
    QueryTimer timer = new QueryTimer();
    int rowsUpdated =
        timer.time(
            "decayDueBatch",
            () ->
                dao.decayDueBatch(
                    curve.getD0(), curve.getD1(), curve.getD2(), curve.getD3(), curve.getD4(),
                    curve.getD5(), curve.getD6(), curve.getD7(), curve.getD8(), curve.getD8Plus(),
                    batchSize));
    return new DecayChunkResult(rowsUpdated, timer.getDurationsMs(), timer.getRowCounts());
  }

  @Override
  public DecayChunkResult creditExpiredSuppressions(int batchSize) {
    QueryTimer timer = new QueryTimer();
    int rowsUpdated = timer.time("creditExpiredSuppressions", () -> dao.creditExpiredSuppressions(batchSize));
    return new DecayChunkResult(rowsUpdated, timer.getDurationsMs(), timer.getRowCounts());
  }
}
