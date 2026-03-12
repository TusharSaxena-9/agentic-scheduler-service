package com.yesmadam.scheduler_service.crontiming.activity;

import java.sql.Timestamp;

import org.springframework.stereotype.Component;

import com.yesmadam.scheduler_service.annotation.HandleExceptions;
import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.crontiming.service.CronTimingService;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of cron timing tracking activities. Reusable across any workflow that needs
 * start/end time tracking in tbl_cron_time.
 */
@Slf4j
@Component
public class CronTimingActivitiesImpl implements CronTimingActivities {

  private final CronTimingService cronTimingService;

  public CronTimingActivitiesImpl(CronTimingService cronTimingService) {
    this.cronTimingService = cronTimingService;
  }

  /**
   * Saves the cron start time and returns the generated ID.
   *
   * @param javaFileName the file name triggering the cron
   * @param methodName the method being executed
   * @param remark description of the cron job
   * @return generated CronTiming ID
   */
  @Override
  @Loggable(operation = "save-cron-start")
  @HandleExceptions
  public Long saveCronStart(String javaFileName, String methodName, String remark) {
    return cronTimingService.saveCronStart(javaFileName, methodName, remark);
  }

  /**
   * Saves the cron start time with platform booking counts and returns the generated ID.
   *
   * @param javaFileName the file name triggering the cron
   * @param methodName the method being executed
   * @param remark description of the cron job
   * @param windowStart start of the booking query window
   * @param windowEnd end of the booking query window
   * @param androidCount Android booking count
   * @param iosCount iOS booking count
   * @return generated CronTiming ID
   */
  @Override
  @Loggable(operation = "save-cron-start-with-counts")
  @HandleExceptions
  public Long saveCronStartWithCounts(
      String javaFileName,
      String methodName,
      String remark,
      Timestamp windowStart,
      Timestamp windowEnd,
      Integer androidCount,
      Integer iosCount) {
    return cronTimingService.saveCronStartWithCounts(
        javaFileName, methodName, remark, windowStart, windowEnd, androidCount, iosCount);
  }

  /**
   * Updates the cron end time for the given CronTiming ID.
   *
   * @param cronTimingId the ID of the CronTiming record to update
   */
  @Override
  @Loggable(operation = "save-cron-end")
  @HandleExceptions
  public void saveCronEnd(Long cronTimingId) {
    cronTimingService.saveCronEnd(cronTimingId);
  }
}
