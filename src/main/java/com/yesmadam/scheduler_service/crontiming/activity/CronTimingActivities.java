package com.yesmadam.scheduler_service.crontiming.activity;

import java.sql.Timestamp;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activity interface for cron execution tracking. Reusable across any workflow that needs start/end
 * time tracking in tbl_cron_time.
 */
@ActivityInterface
public interface CronTimingActivities {

  /** Saves cron start time. Use for standard workflows with no platform counts. */
  @ActivityMethod
  Long saveCronStart(String javaFileName, String methodName, String remark);

  /**
   * Saves cron start time with platform booking counts and query window. Use for booking monitor
   * workflows.
   */
  @ActivityMethod
  Long saveCronStartWithCounts(
      String javaFileName,
      String methodName,
      String remark,
      Timestamp windowStart,
      Timestamp windowEnd,
      Integer androidCount,
      Integer iosCount);

  /** Saves cron end time for the given CronTiming ID. */
  @ActivityMethod
  void saveCronEnd(Long cronTimingId);
}
