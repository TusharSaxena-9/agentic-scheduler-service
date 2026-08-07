package com.yesmadam.schedulerservice.jobs.crontiming.service;

import java.sql.Timestamp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.crontiming.model.CronTiming;
import com.yesmadam.schedulerservice.jobs.crontiming.repository.CronTimingRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing cron execution tracking. Provides methods to record start and end times for
 * any cron workflow.
 */
@Slf4j
@Service
public class CronTimingService {

  private final CronTimingRepository cronTimingRepository;

  public CronTimingService(CronTimingRepository cronTimingRepository) {
    this.cronTimingRepository = cronTimingRepository;
  }

  /**
   * Saves a new CronTiming record with start time and returns the generated ID. Use this for
   * standard cron workflows that don't track platform counts.
   *
   * @param javaFileName the file name triggering the cron
   * @param methodName the method being executed
   * @param remark description of the cron job
   * @return generated CronTiming ID
   */
  @Transactional
  public Long saveCronStart(String javaFileName, String methodName, String remark) {
    CronTiming cronTiming = new CronTiming();
    cronTiming.setCronStartAt(new Timestamp(System.currentTimeMillis()));
    cronTiming.setCreatedAt(new Timestamp(System.currentTimeMillis()));
    cronTiming.setJavaFileName(javaFileName);
    cronTiming.setMethodName(methodName);
    cronTiming.setRemark(remark);
    return cronTimingRepository.save(cronTiming).getId();
  }

  /**
   * Saves a new CronTiming record with platform booking counts and query window. Use this for
   * booking monitor workflows that track Android/iOS counts.
   *
   * @param javaFileName the file name triggering the cron
   * @param methodName the method being executed
   * @param remark description of the cron job
   * @param windowStart start of the booking query window
   * @param windowEnd end of the booking query window
   * @param androidCount number of Android bookings in the window
   * @param iosCount number of iOS bookings in the window
   * @return generated CronTiming ID
   */
  @Transactional
  public Long saveCronStartWithCounts(
      String javaFileName,
      String methodName,
      String remark,
      Timestamp windowStart,
      Timestamp windowEnd,
      Integer androidCount,
      Integer iosCount) {
    CronTiming cronTiming = new CronTiming();
    cronTiming.setCronStartAt(new Timestamp(System.currentTimeMillis()));
    cronTiming.setCreatedAt(new Timestamp(System.currentTimeMillis()));
    cronTiming.setJavaFileName(javaFileName);
    cronTiming.setMethodName(methodName);
    cronTiming.setRemark(remark);
    cronTiming.setStart(windowStart);
    cronTiming.setEnd(windowEnd);
    cronTiming.setAndroid(androidCount);
    cronTiming.setIos(iosCount);
    return cronTimingRepository.save(cronTiming).getId();
  }

  /**
   * Updates the end time for the given CronTiming record.
   *
   * <p>Runs in a read-write transaction so the lookup hits the writer: the record was created
   * moments earlier and may not have replicated to the reader yet.
   *
   * @param cronTimingId the ID of the record to update
   */
  @Transactional
  public void saveCronEnd(Long cronTimingId) {
    cronTimingRepository
        .findById(cronTimingId)
        .ifPresent(
            cronTiming -> {
              cronTiming.setCronEndAt(new Timestamp(System.currentTimeMillis()));
              cronTimingRepository.save(cronTiming);
            });
  }
}
