package com.yesmadam.schedulerservice.jobs.userwalletsum.workflow;

import java.time.Duration;

import org.slf4j.Logger;

import com.yesmadam.schedulerservice.jobs.userwalletsum.activity.UserWalletSumActivities;
import com.yesmadam.schedulerservice.jobs.userwalletsum.model.UserWalletSumReport;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Implementation of the daily user wallet sum snapshot: one aggregate read, one snapshot write, one
 * log line. Port of {@code UserWalletSumCron#userWalletSum} from messagingapis onto Temporal.
 *
 * <p>Pure orchestration — no Spring injection, no clock reads, no {@code @Loggable} on {@link
 * #run()} (CLAUDE.md rule 1). Logging goes through {@link Workflow#getLogger} rather than a {@code
 * @Slf4j} field, since a plain logger re-emits every line on replay. Deliberately does not write
 * {@code tbl_cron_time} — rule 8 says new jobs must not.
 */
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")
public class UserWalletSumWorkflowImpl implements UserWalletSumWorkflow {

  private static final Logger log = Workflow.getLogger(UserWalletSumWorkflowImpl.class);

  /**
   * Two activities, not one, so a failure in the (cheap) save does not force a retry of the
   * (potentially expensive, unindexed full-table) compute — CLAUDE.md's note on retry amplification
   * under rule 4. Both are quick DB calls, so one set of options covers the stub.
   */
  private final UserWalletSumActivities activities =
      Workflow.newActivityStub(
          UserWalletSumActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(Duration.ofMinutes(20))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
              .build());

  @Override
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();
    log.info("event=workflow_started workflow_id={}", workflowId);

    UserWalletSumReport report = activities.computeWalletSum();
    activities.saveDailyBalance(report);

    log.info(
        "event=workflow_completed workflow_id={} main_wallet_sum={} seemless_wallet_sum={}",
        workflowId,
        report.getMainWalletSum(),
        report.getSeemlessWalletSum());
  }
}
