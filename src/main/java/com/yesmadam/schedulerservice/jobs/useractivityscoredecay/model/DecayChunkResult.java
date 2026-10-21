package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DecayChunkResult {

  private int rowsUpdated;

  private Map<String, Long> queryDurationsMs;

  private Map<String, Integer> queryRowCounts;
}
