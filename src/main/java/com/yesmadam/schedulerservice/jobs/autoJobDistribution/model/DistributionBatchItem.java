package com.yesmadam.schedulerservice.jobs.autoJobDistribution.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One order's distribution instruction, POSTed to admin's {@code
 * /api/yesmadam/distribute-job-batch}.
 *
 * <p><b>Field names are a wire contract with admin</b> — they are serialised as-is and admin
 * deserialises into its own {@code DistributionBatchItem}. Renaming any of them silently breaks the
 * call (admin sees nulls), so they stay exactly as cron sent them.
 *
 * <p>This service owns the round decision; {@code expireDistributionId} names the live round admin
 * must close as part of an advance, and is {@code null} for a fresh Round 1.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistributionBatchItem {

  private Long orderId;
  private Long expireDistributionId;
  private Integer round;
  private Integer incentive;
}
