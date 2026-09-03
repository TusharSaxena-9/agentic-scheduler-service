package com.yesmadam.schedulerservice.jobs.spwalletsum.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.spwalletsum.activity.SpWalletSumActivities;
import com.yesmadam.schedulerservice.jobs.spwalletsum.model.SpWalletSumReport;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the daily SP wallet sum snapshot: two aggregate reads, one snapshot write, one
 * log line. Port of {@code SpWalletSumCron#spWalletSum} from messagingapis onto Temporal.
 *
 * <p>Pure orchestration — no Spring injection, no clock reads, no {@code @Loggable} on {@link
 * #run()} (CLAUDE.md rule 1). Logging goes through {@link Workflow#getLogger} rather than a {@code
 * @Slf4j} field, since a plain logger re-emits every line on replay. Deliberately does not write
 * {@code tbl_cron_time} — rule 8 says new jobs must not.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.light}")
public class SpWalletSumWorkflowImpl implements SpWalletSumWorkflow {

  private static final Logger log = Workflow.getLogger(SpWalletSumWorkflowImpl.class);

  /**
   * Two activities, not one, so a failure in the (cheap) save does not force a retry of the
   * (two-query) compute — CLAUDE.md's note on retry amplification under rule 4. Both are quick DB
   * calls against tables far smaller than {@code tbl_user}, so one set of options covers the stub;
   * sized from the "query or batch write over a bounded set" row of rule 4's placeholder table
   * pending a real measured duration.
   */
  private final SpWalletSumActivities activities =
      Workflow.newActivityStub(
          SpWalletSumActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    SpWalletSumReport report = activities.computeWalletSum();
    activities.saveDailyBalance(report);

    log.info(
        "event=workflow_completed workflow_id={} main_active={} saving_active={} main_deactive={}"
            + " saving_deactive={}",
        workflowId,
        report.getMainActiveWalletSum(),
        report.getSavingActiveWalletSum(),
        report.getMainDeActiveWalletSum(),
        report.getSavingDeActiveWalletSum());
  }
}
