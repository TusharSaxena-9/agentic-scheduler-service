package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.activity;

import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.model.SpTomorrowJobReminderBatch;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;


@ActivityInterface
public interface SpTomorrowJobReminderActivities {


  @ActivityMethod
  SpTomorrowJobReminderBatch fetchTomorrowReminders();


  @ActivityMethod
  void sendTomorrowReminders(SpTomorrowJobReminderBatch batch);
}
