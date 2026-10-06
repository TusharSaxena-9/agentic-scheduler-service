package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.model.SpTomorrowJobReminderBatch;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.service.SpTomorrowJobReminderService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class SpTomorrowJobReminderActivitiesImpl implements SpTomorrowJobReminderActivities {

  private final SpTomorrowJobReminderService spTomorrowJobReminderService;

  public SpTomorrowJobReminderActivitiesImpl(
      SpTomorrowJobReminderService spTomorrowJobReminderService) {
    this.spTomorrowJobReminderService = spTomorrowJobReminderService;
  }

  @Override
  @Loggable(operation = "fetch-sp-tomorrow-job-reminders")
  @HandleExceptions
  public SpTomorrowJobReminderBatch fetchTomorrowReminders() {
    return spTomorrowJobReminderService.fetchTomorrowReminders();
  }

  @Override
  @Loggable(operation = "send-sp-tomorrow-job-reminders")
  @HandleExceptions
  public void sendTomorrowReminders(SpTomorrowJobReminderBatch batch) {
    spTomorrowJobReminderService.sendReminders(batch);
  }
}
