package com.yesmadam.schedulerservice.config;

import java.time.Duration;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import lombok.Getter;
import lombok.Setter;

/**
 * One scheduled job, as declared in {@code schedules.yml}.
 *
 * <p>Everything about when a job runs lives here rather than being spread across a
 * {@code @Configuration} class, a properties key and an annotation. At a hundred jobs that matters
 * for a specific reason: "what runs at midnight, and who owns it" needs one answer, and today it
 * has three sources.
 *
 * <p>Optional policy fields left null inherit the service-wide defaults applied by {@link
 * TemporalScheduleRegistrar}. Inheriting is fine; inheriting without having considered it is what
 * rule 5 in {@code CLAUDE.md} is about.
 */
@Getter
@Setter
public class JobDefinition {

  /**
   * Stable job name in kebab-case. The schedule and workflow IDs derive from it — {@code
   * <name>-schedule} and {@code <name>-workflow} — so changing it orphans the existing schedule
   * rather than renaming it.
   */
  private String name;

  /** Fully-qualified name of the {@code @WorkflowInterface} this schedule starts. */
  private String workflow;

  /**
   * Which task queue tier the job runs on. An unknown value fails at binding rather than silently
   * resolving to a queue nobody polls. See rule 6 in {@code CLAUDE.md} for how to choose.
   */
  private TaskQueueTier tier;

  /** Five-field Temporal cron expression. Note Spring's six-field format does not port directly. */
  private String cron;

  /** IANA timezone the cron is evaluated in. Null inherits the default. */
  private String timeZone;

  /** Overlap policy. Null inherits the default. */
  private ScheduleOverlapPolicy overlap;

  /** How late a missed action may still be taken. Null inherits the default. */
  private Duration catchupWindow;

  /** Spread applied to each firing. Null inherits the default; {@code PT0S} pins an exact time. */
  private Duration jitter;

  /** Wall-clock bound on a single run. Null inherits the default. */
  private Duration runTimeout;

  /** Whether a failed or timed-out run pauses the schedule. Null inherits the default. */
  private Boolean pauseOnFailure;

  /**
   * Whether the schedule runs when first created.
   *
   * <p>Always sent, so {@code temporal.schedule.defaults.create-paused} does not apply to jobs
   * declared here — that default governs the programmatic path only.
   *
   * <p>Applies on <b>creation only</b>. Once a schedule exists its paused state belongs to whoever
   * is operating it — an engineer who paused a job during an incident must not have a deploy resume
   * it. Flipping this on an existing schedule does nothing; pause and unpause from the UI or CLI.
   */
  private boolean enabled;

  /** Owning team. Not used by Temporal — it is here so the answer exists in one place. */
  private String owner;

  /** Free-text note on what the job does, surfaced in audit logs. */
  private String description;

  /** Temporal schedule ID, derived from the name. */
  public String scheduleId() {
    return name + "-schedule";
  }

  /** Workflow ID for each run. Temporal appends the nominal scheduled time to it. */
  public String workflowId() {
    return name + "-workflow";
  }
}
