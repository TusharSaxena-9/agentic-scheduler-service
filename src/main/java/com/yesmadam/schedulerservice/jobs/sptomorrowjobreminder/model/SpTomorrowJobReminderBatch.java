package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpTomorrowJobReminderBatch {

  private LocalDate jobDate;
  private List<Integer> spIds;
  private Map<String, Long> queryDurationsMs;
  private Map<String, Integer> queryRowCounts;

  public SpTomorrowJobReminderBatch(LocalDate jobDate, List<Integer> spIds) {
    this(jobDate, spIds, new LinkedHashMap<>(), new LinkedHashMap<>());
  }
}
