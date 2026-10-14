package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.service;

import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.model.SpTomorrowJobReminderBatch;

public interface SpTomorrowJobReminderService {


  SpTomorrowJobReminderBatch fetchTomorrowReminders();


  void sendReminders(SpTomorrowJobReminderBatch batch);
}
