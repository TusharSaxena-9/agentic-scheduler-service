package com.yesmadam.schedulerservice.common.temporal;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.logging.LoggingUtils;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import lombok.extern.slf4j.Slf4j;

/**
 * Registers cron-triggered Temporal schedules with the conventions shared by all jobs in this
 * service.
 *
 * <p>This is the single place every schedule passes through, which makes it the right place to
 * apply service-wide policy. Each of overlap, catchup window, jitter, run timeout, pause-on-failure
 * and paused-on-create has a default from {@code temporal.schedule.defaults.*}; a job that needs
 * different semantics sets the corresponding field on its {@link CronScheduleRequest} and overrides
 * it. Nothing is left to Temporal's own defaults, because those are tuned for a different shape of
 * workload than a fleet of database-heavy periodic jobs — notably a one-year catchup window, which
 * turns a recovered outage into a stampede.
 *
 * <p>{@link #createIfAbsent} is create-only. It does not update a schedule that already exists,
 * which means configuration describes what a schedule looked like when it was first registered
 * rather than what it looks like now. That is deliberate: an in-place update would also overwrite
 * paused state, and a job paused during an incident must stay paused across the next deploy. The
 * cost is that applying a config change is a manual delete in the Temporal UI followed by a
 * restart.
 */
@Slf4j
@Component
public class TemporalScheduleRegistrar {

  private final ScheduleClient scheduleClient;

  private final String defaultTimeZone;
  private final ScheduleOverlapPolicy defaultOverlapPolicy;
  private final Duration defaultCatchupWindow;
  private final Duration defaultJitter;
  private final Duration defaultRunTimeout;
  private final boolean defaultPauseOnFailure;
  private final boolean defaultCreatePaused;

  public TemporalScheduleRegistrar(
      ScheduleClient scheduleClient,
      @Value("${temporal.schedule.defaults.time-zone:Asia/Kolkata}") String defaultTimeZone,
      @Value("${temporal.schedule.defaults.overlap-policy:SCHEDULE_OVERLAP_POLICY_SKIP}")
          ScheduleOverlapPolicy defaultOverlapPolicy,
      @Value("${temporal.schedule.defaults.catchup-window:PT5M}") Duration defaultCatchupWindow,
      @Value("${temporal.schedule.defaults.jitter:PT1M}") Duration defaultJitter,
      @Value("${temporal.schedule.defaults.run-timeout:PT1H}") Duration defaultRunTimeout,
      @Value("${temporal.schedule.defaults.pause-on-failure:false}") boolean defaultPauseOnFailure,
      @Value("${temporal.schedule.defaults.create-paused:true}") boolean defaultCreatePaused) {
    this.scheduleClient = scheduleClient;
    this.defaultTimeZone = defaultTimeZone;
    this.defaultOverlapPolicy = defaultOverlapPolicy;
    this.defaultCatchupWindow = defaultCatchupWindow;
    this.defaultJitter = defaultJitter;
    this.defaultRunTimeout = defaultRunTimeout;
    this.defaultPauseOnFailure = defaultPauseOnFailure;
    this.defaultCreatePaused = defaultCreatePaused;
  }

  /** Memo key marking a schedule as owned by the reconciler. */
  public static final String MANAGED_BY_MEMO_KEY = "managedBy";

  /** Memo value written into {@link #MANAGED_BY_MEMO_KEY}. */
  public static final String MANAGED_BY_MEMO_VALUE = "schedules.yml";

  /**
   * Creates the schedule if it does not already exist, and leaves it alone if it does.
   *
   * <p>Create-only by choice. A change to a cron, a tier or a policy does not reach a schedule that
   * is already there — delete it from the Temporal UI and the next startup recreates it from
   * configuration.
   *
   * @param request the schedule definition to register
   */
  public void createIfAbsent(CronScheduleRequest request) {
    String scheduleId = request.getScheduleId();
    boolean createPaused = orDefault(request.getCreatePaused(), defaultCreatePaused);

    LoggingUtils.setCorrelationId(scheduleId);

    try {
      scheduleClient.createSchedule(
          scheduleId,
          buildSchedule(request, createPaused),
          ScheduleOptions.newBuilder()
              .setMemo(Map.of(MANAGED_BY_MEMO_KEY, MANAGED_BY_MEMO_VALUE))
              .build());

      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATED",
          scheduleId,
          String.format(
              "%s cron=%s", describe(request, createPaused), request.getCronExpression()));

    } catch (ScheduleAlreadyRunningException ex) {
      log.info(
          "event=schedule_exists schedule_id={} action=left_unchanged "
              + "note=delete_in_ui_to_apply_config_changes",
          scheduleId);

    } catch (RuntimeException ex) {
      LoggingUtils.logAuditEvent(
          "SCHEDULE_CREATE_FAILED", scheduleId, String.format("error=%s", ex.getMessage()));
      throw ex;

    } finally {
      LoggingUtils.clearContext();
    }
  }

  /** Renders the resolved policy for the audit log, so the defaults a job inherited are visible. */
  private String describe(CronScheduleRequest request, boolean paused) {
    return String.format(
        "queue=%s timezone=%s overlap=%s catchup=%s jitter=%s run_timeout=%s paused=%s",
        request.getTaskQueue(),
        orDefault(request.getTimeZone(), defaultTimeZone),
        orDefault(request.getOverlapPolicy(), defaultOverlapPolicy),
        orDefault(request.getCatchupWindow(), defaultCatchupWindow),
        orDefault(request.getJitter(), defaultJitter),
        orDefault(request.getRunTimeout(), defaultRunTimeout),
        paused);
  }

  /**
   * Builds the schedule, filling any policy the request left unset from the service-wide defaults.
   *
   * <p>The paused flag is set on {@link ScheduleState} here rather than by a follow-up {@code
   * pause()} call, which would be a second RPC with a window in which a dying pod leaves a schedule
   * running that was meant to be paused.
   */
  private Schedule buildSchedule(CronScheduleRequest request, boolean paused) {
    ScheduleSpec spec =
        ScheduleSpec.newBuilder()
            .setCronExpressions(Collections.singletonList(request.getCronExpression()))
            .setTimeZoneName(orDefault(request.getTimeZone(), defaultTimeZone))
            .setJitter(orDefault(request.getJitter(), defaultJitter))
            .build();

    SchedulePolicy policy =
        SchedulePolicy.newBuilder()
            .setOverlap(orDefault(request.getOverlapPolicy(), defaultOverlapPolicy))
            .setCatchupWindow(orDefault(request.getCatchupWindow(), defaultCatchupWindow))
            .setPauseOnFailure(orDefault(request.getPauseOnFailure(), defaultPauseOnFailure))
            .build();

    ScheduleState state =
        ScheduleState.newBuilder()
            .setPaused(paused)
            .setNote("Managed by " + MANAGED_BY_MEMO_VALUE)
            .build();

    return Schedule.newBuilder()
        .setSpec(spec)
        .setPolicy(policy)
        .setState(state)
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(request.getWorkflowType())
                .setOptions(
                    WorkflowOptions.newBuilder()
                        .setTaskQueue(request.getTaskQueue())
                        .setWorkflowId(request.getWorkflowId())
                        .setWorkflowRunTimeout(
                            orDefault(request.getRunTimeout(), defaultRunTimeout))
                        .build())
                .build())
        .build();
  }

  private static <T> T orDefault(T requested, T fallback) {
    return Objects.nonNull(requested) ? requested : fallback;
  }
}
