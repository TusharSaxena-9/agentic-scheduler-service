package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.activity;

import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderFetchResult;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderStage;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface FeedbackReminderActivities {

  @ActivityMethod
  FeedbackReminderFetchResult fetchPendingFeedbackOrders(
      long nominalFireTimeMillis, FeedbackReminderStage stage);

  @ActivityMethod
  int sendFeedbackReminders(FeedbackReminderFetchResult fetchResult);
}
