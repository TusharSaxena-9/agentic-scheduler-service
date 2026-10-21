package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayChunkResult;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayMultiplierCurve;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.service.UserActivityScoreDecayService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class UserActivityScoreDecayActivitiesImpl implements UserActivityScoreDecayActivities {

  private final UserActivityScoreDecayService userActivityScoreDecayService;

  public UserActivityScoreDecayActivitiesImpl(
      UserActivityScoreDecayService userActivityScoreDecayService) {
    this.userActivityScoreDecayService = userActivityScoreDecayService;
  }

  @Override
  @Loggable(operation = "fetch-decay-multiplier-curve")
  @HandleExceptions
  public DecayMultiplierCurve fetchDecayMultiplierCurve() {
    return userActivityScoreDecayService.fetchMultiplierCurve();
  }

  @Override
  @Loggable(operation = "decay-user-activity-score-due-batch")
  @HandleExceptions
  public DecayChunkResult decayDueBatch(DecayMultiplierCurve curve, int batchSize) {
    return userActivityScoreDecayService.decayDueBatch(curve, batchSize);
  }

  @Override
  @Loggable(operation = "credit-expired-suppressions-batch")
  @HandleExceptions
  public DecayChunkResult creditExpiredSuppressions(int batchSize) {
    return userActivityScoreDecayService.creditExpiredSuppressions(batchSize);
  }
}
