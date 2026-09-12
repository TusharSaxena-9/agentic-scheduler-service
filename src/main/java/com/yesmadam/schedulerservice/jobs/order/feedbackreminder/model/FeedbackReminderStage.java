package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model;

import java.time.Duration;

public enum FeedbackReminderStage {
  THREE_HOURS(Duration.ofHours(3)),
  TWENTY_FOUR_HOURS(Duration.ofHours(24)),
  FORTY_EIGHT_HOURS(Duration.ofHours(48));

  private final Duration offsetAfterCompletion;

  FeedbackReminderStage(Duration offsetAfterCompletion) {
    this.offsetAfterCompletion = offsetAfterCompletion;
  }

  /** How long after {@code order_end_time_with_date} this checkpoint's window sits. */
  public Duration getOffsetAfterCompletion() {
    return offsetAfterCompletion;
  }
}
