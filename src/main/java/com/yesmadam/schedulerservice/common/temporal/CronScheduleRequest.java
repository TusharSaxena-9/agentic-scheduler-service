package com.yesmadam.schedulerservice.common.temporal;

import java.time.Duration;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import lombok.Builder;
import lombok.Value;

/**
 * Everything needed to register a cron-triggered Temporal schedule.
 *
 * <p>The policy fields below are deliberately nullable. A job that leaves one unset inherits the
 * service-wide default from {@code temporal.schedule.defaults.*}; a job that sets one overrides it.
 * That keeps the common case free of boilerplate while still letting a job state a policy its own
 * semantics require — a monitor that should drop late runs and a financial job that must never miss
 * one need different answers, and neither should be decided by accident.
 *
 * @see TemporalScheduleRegistrar
 */
@Value
@Builder
public class CronScheduleRequest {

  /** Unique Temporal schedule identifier. */
  String scheduleId;

  /** Cron expression that determines when the workflow fires. Five-field standard cron. */
  String cronExpression;

  /** Task queue the workflow executes on. */
  String taskQueue;

  /** Workflow interface annotated with {@code @WorkflowInterface}. */
  Class<?> workflowType;

  /** Workflow ID assigned to each scheduled run; Temporal appends the nominal scheduled time. */
  String workflowId;

  /**
   * IANA timezone the cron expression is evaluated in. {@code null} inherits the default.
   *
   * <p>Note this is the zone the <em>schedule</em> fires in, which is a separate thing from the JVM
   * zone a job's own date arithmetic uses.
   */
  String timeZone;

  /**
   * What happens when a run is still going as the next one falls due. {@code null} inherits the
   * default.
   *
   * <p>Temporal's own default is SKIP, which silently drops the later run — right for a monitor
   * whose next tick re-observes the same condition, wrong for anything whose window is not
   * re-examined later.
   */
  ScheduleOverlapPolicy overlapPolicy;

  /**
   * How late an action may be taken after the service was unavailable. {@code null} inherits the
   * default.
   *
   * <p>Temporal's own default is one year, so an outage is followed by a stampede of backfilled
   * actions. Short for frequent jobs, longer for a daily aggregation whose late run is still
   * useful.
   */
  Duration catchupWindow;

  /**
   * Random spread applied to each firing, derived from the schedule ID so a given schedule's offset
   * is stable. {@code null} inherits the default. Use {@link Duration#ZERO} for a job that must
   * fire at an exact time.
   */
  Duration jitter;

  /**
   * Wall-clock bound on a single run. {@code null} inherits the default.
   *
   * <p>Without one, a wedged run stays Running forever and — under SKIP — suppresses every
   * subsequent firing of the schedule with no failed workflow to alert on. Size at roughly twice
   * the job's p99.
   */
  Duration runTimeout;

  /**
   * Whether a failed or timed-out run pauses the schedule. {@code null} inherits the default.
   *
   * <p>Worth enabling for jobs where a repeated failure should stop and be looked at rather than
   * continue firing — anything that mails real people, for instance.
   */
  Boolean pauseOnFailure;

  /**
   * Whether the schedule is created paused. {@code null} inherits the default.
   *
   * <p>Paused-on-create is a safety catch for a new job. It does not scale to a whole namespace
   * rebuild, where nobody remembers which of a hundred schedules should be running — so declare it
   * per job rather than relying on it as a blanket policy.
   */
  Boolean createPaused;
}
