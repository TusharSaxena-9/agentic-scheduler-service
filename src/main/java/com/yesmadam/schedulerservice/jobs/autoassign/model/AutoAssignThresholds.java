package com.yesmadam.schedulerservice.jobs.autoassign.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Auto-assign gates resolved from {@code tbl_settings} once per run and passed to every chunk
 * activity, so all chunks of a run apply identical gates even if settings change mid-run.
 *
 * <p>Serialized into Temporal workflow history — keep Jackson-friendly (no-args constructor,
 * getters/setters).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AutoAssignThresholds {

  /** Reassign attempts at or above which the order goes to distribution instead of auto-assign. */
  private int reassignThreshold;

  /** Minutes-to-start below which the order goes to distribution regardless of reassign count. */
  private int priorityTimeThresholdMinutes;

  /** Minimum minutes-to-start required for an order to be a candidate at all. */
  private int minLeadTimeMinutes;

  /**
   * Minutes-to-start below which a fresh, express order may still be auto-assigned as a last
   * resort, bypassing every other check.
   */
  private int expressMinMinutesBeforeStart;

  /** How recently, in minutes, an express order must have been created for that bypass to apply. */
  private int expressOrderAgeLookbackMinutes;
}
