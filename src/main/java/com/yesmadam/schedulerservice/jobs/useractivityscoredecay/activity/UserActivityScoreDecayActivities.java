package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.activity;

import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayChunkResult;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayMultiplierCurve;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface UserActivityScoreDecayActivities {

  @ActivityMethod
  DecayMultiplierCurve fetchDecayMultiplierCurve();

  @ActivityMethod
  DecayChunkResult decayDueBatch(DecayMultiplierCurve curve, int batchSize);

  @ActivityMethod
  DecayChunkResult creditExpiredSuppressions(int batchSize);
}
