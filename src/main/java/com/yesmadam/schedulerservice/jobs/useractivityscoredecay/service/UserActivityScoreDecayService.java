package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.service;

import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayChunkResult;
import com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model.DecayMultiplierCurve;

public interface UserActivityScoreDecayService {

  DecayMultiplierCurve fetchMultiplierCurve();

  DecayChunkResult decayDueBatch(DecayMultiplierCurve curve, int batchSize);

  DecayChunkResult creditExpiredSuppressions(int batchSize);
}
