package com.yesmadam.schedulerservice.jobs.sebaltagging.enums;

/**
 * How one tagging run treats the running aggregate in {@code tbl_sp_sebal_subcategory_tags}.
 *
 * <p>{@code INCREMENTAL} is the default: it only folds in first-order cohorts whose 90-day rebook
 * window has newly closed since {@link com.yesmadam.persistence.SebalTaggingWatermark#getProcessedUntil()}.
 * {@code FULL} rebuilds the running aggregate from the entire 2022-onward population — required
 * the first time the job ever runs (no watermark yet), and available to force manually afterwards
 * if historical order data is corrected retroactively and the running totals need to be rebuilt
 * rather than trusted as a base to add deltas onto.
 */
public enum SebalTaggingRunMode {
  FULL,
  INCREMENTAL
}
