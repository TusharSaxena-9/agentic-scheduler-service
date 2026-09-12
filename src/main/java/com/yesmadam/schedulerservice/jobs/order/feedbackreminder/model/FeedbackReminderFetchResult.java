package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackReminderFetchResult {

  private FeedbackReminderStage stage;
  private Timestamp windowStart;
  private Timestamp windowEnd;
  private List<PendingFeedbackOrder> orders;
  private Map<String, Long> queryDurationsMs;
  private Map<String, Integer> queryRowCounts;

  public FeedbackReminderFetchResult(
      FeedbackReminderStage stage,
      Timestamp windowStart,
      Timestamp windowEnd,
      List<PendingFeedbackOrder> orders) {
    this(stage, windowStart, windowEnd, orders, new LinkedHashMap<>(), new LinkedHashMap<>());
  }
}
