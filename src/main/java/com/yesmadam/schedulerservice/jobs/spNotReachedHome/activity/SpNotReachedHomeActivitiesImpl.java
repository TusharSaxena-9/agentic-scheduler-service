package com.yesmadam.schedulerservice.jobs.spNotReachedHome.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.service.SpNotReachedHomeService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/** Thin adapter between Temporal and {@link SpNotReachedHomeService} — no logic lives here. */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpNotReachedHomeActivitiesImpl implements SpNotReachedHomeActivities {

  private final SpNotReachedHomeService spNotReachedHomeService;

  public SpNotReachedHomeActivitiesImpl(SpNotReachedHomeService spNotReachedHomeService) {
    this.spNotReachedHomeService = spNotReachedHomeService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "remove-sp-with-active-job")
  @HandleExceptions
  public int removeSpWithActiveJob() {
    return spNotReachedHomeService.removeSpWithActiveJob();
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "insert-sp-not-reached-home")
  @HandleExceptions
  public int insertSpNotReachedHome() {
    return spNotReachedHomeService.insertSpNotReachedHome();
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "auto-mark-reached-home")
  @HandleExceptions
  public int autoMarkReachedHome() {
    return spNotReachedHomeService.autoMarkReachedHome();
  }
}
