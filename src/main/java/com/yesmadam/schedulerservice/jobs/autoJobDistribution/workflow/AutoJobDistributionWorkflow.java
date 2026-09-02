package com.yesmadam.schedulerservice.jobs.autoJobDistribution.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Advances Auto Job Distribution rounds. Runs every 5 minutes during operating hours — mirrors
 * cron's {@code JobDistributionConstants.CRON_EXPRESSION} (SAAB-349).
 */
@WorkflowInterface
public interface AutoJobDistributionWorkflow {

  @WorkflowMethod
  void run();
}
