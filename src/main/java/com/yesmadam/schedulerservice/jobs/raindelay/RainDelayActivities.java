package com.yesmadam.schedulerservice.jobs.raindelay;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Split at the send boundary on purpose: WhatsApp cannot be rolled back, so a retry of a combined
 * query-and-send would re-message everyone already reached.
 */
@ActivityInterface
public interface RainDelayActivities {

  /**
   * Reads {@code rainHub}, finds hubs with bookings in this run's slot, looks up each one's weather.
   *
   * @param nominalFireTimeMillis the run's scheduled time — the window is derived from it, never
   *     from wall-clock now, so consecutive tiles cannot gap or overlap
   */
  @ActivityMethod
  RainyHubResolution resolveRainyHubs(long nominalFireTimeMillis);

  /**
   * Messages customers at the rainy hubs. <b>Not idempotent</b> — duplicates are prevented by the
   * windows not overlapping, so a retry would re-send. The stub pins attempts to 1.
   */
  @ActivityMethod
  RainDelayNotifyResult notifyRainyHubs(RainyHubResolution resolution);
}
