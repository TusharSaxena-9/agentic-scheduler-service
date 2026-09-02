package com.yesmadam.schedulerservice.jobs.spNotReachedHome.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activities for the SP-not-reached-home tracker.
 *
 * <p>The original cron ran its three operations inline in one method, so a failure part-way through
 * lost the whole cycle. They are three activities here because they are genuinely independent
 * writes with a required order, so each retries on its own and the workflow guarantees the
 * sequence. That ordering is not cosmetic: the delete must precede the upsert, or a row for an SP
 * who has since taken another job gets refreshed by the UPSERT instead of removed.
 */
@ActivityInterface
public interface SpNotReachedHomeActivities {

  /**
   * Drops today's rows for SPs whose latest booking is no longer complete.
   *
   * @return rows deleted
   */
  @ActivityMethod
  int removeSpWithActiveJob();

  /**
   * Finds SPs further than the configured threshold from home and upserts them into the tracker.
   *
   * @return rows upserted
   */
  @ActivityMethod
  int insertSpNotReachedHome();

  /**
   * Marks SPs who have since returned within the threshold.
   *
   * @return rows marked as reached home
   */
  @ActivityMethod
  int autoMarkReachedHome();
}
