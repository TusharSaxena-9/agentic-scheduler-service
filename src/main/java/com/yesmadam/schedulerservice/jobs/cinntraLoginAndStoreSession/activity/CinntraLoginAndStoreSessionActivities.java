package com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity for the Cinntra login/session-refresh cron. A single method, matching
 * tracking's single-method cron — there is no separate "fetch eligible" step here.
 */
@ActivityInterface
public interface CinntraLoginAndStoreSessionActivities {

  /** Logs in to Cinntra and upserts the resulting session id, matching tracking's {@code loginAndStoreSession}. */
  @ActivityMethod
  void loginAndStoreSession();
}
