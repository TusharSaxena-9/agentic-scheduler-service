package com.yesmadam.schedulerservice.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.yesmadam.schedulerservice.common.temporal.CronScheduleRequest;
import com.yesmadam.schedulerservice.common.temporal.TemporalScheduleRegistrar;
import com.yesmadam.schedulerservice.logging.LoggingUtils;

import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleListDescription;
import io.temporal.workflow.WorkflowInterface;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes Temporal's schedules match {@code schedules.yml} on startup.
 *
 * <p>Without this the service can only create schedules that do not yet exist, so configuration
 * describes what was true on the day a schedule was first registered. Changing a cron, moving a job
 * to a different task queue, or adding an overlap policy has no effect on anything already running
 * — and the log says "already exists", which reads like success.
 *
 * <p>Three deliberate choices about what this does <em>not</em> do:
 *
 * <ul>
 *   <li><b>Paused state is never asserted.</b> {@link TemporalScheduleRegistrar} reads it from the
 *       live schedule and carries it forward. A job paused during an incident stays paused across
 *       deploys; {@code enabled} in the YAML applies only when the schedule is first created.
 *   <li><b>Nothing is ever deleted.</b> Schedules that carry this service's memo but have left the
 *       YAML are reported as orphans and left alone. Removing a block of YAML should not silently
 *       delete a production schedule.
 *   <li><b>Failure is not fatal.</b> This runs on {@link ApplicationReadyEvent} rather than
 *       {@code @PostConstruct} precisely so that a Temporal blip during a rollout does not fail the
 *       application context and crashloop a pod whose workers are otherwise healthy.
 * </ul>
 *
 * <p>Schedules created outside this file — programmatically, or by hand in the UI — carry no memo
 * and are not touched.
 */
@Slf4j
@Component
public class ScheduleReconciler {

  private final JobScheduleProperties properties;
  private final TemporalScheduleRegistrar registrar;
  private final ScheduleClient scheduleClient;

  /**
   * Tier to task queue name. The names live in {@code temporal.taskqueue.*} rather than on the enum
   * because the {@code @WorkflowImpl} annotations and the {@code spring.temporal.workers[*]}
   * capacity blocks resolve the same properties — one source keeps a rename from leaving a schedule
   * pointed at a queue no worker polls.
   */
  private final EnumMap<TaskQueueTier, String> queueByTier = new EnumMap<>(TaskQueueTier.class);

  public ScheduleReconciler(
      JobScheduleProperties properties,
      TemporalScheduleRegistrar registrar,
      ScheduleClient scheduleClient,
      @Value("${temporal.taskqueue.light}") String lightQueue,
      @Value("${temporal.taskqueue.standard}") String standardQueue,
      @Value("${temporal.taskqueue.heavy}") String heavyQueue,
      @Value("${temporal.taskqueue.notification}") String notificationQueue) {
    this.properties = properties;
    this.registrar = registrar;
    this.scheduleClient = scheduleClient;
    queueByTier.put(TaskQueueTier.LIGHT, lightQueue);
    queueByTier.put(TaskQueueTier.STANDARD, standardQueue);
    queueByTier.put(TaskQueueTier.HEAVY, heavyQueue);
    queueByTier.put(TaskQueueTier.NOTIFICATION, notificationQueue);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reconcileOnStartup() {
    if (!properties.isReconcileOnStartup()) {
      log.info("event=schedule_registration_skipped reason=disabled_by_config");
      return;
    }

    List<JobDefinition> jobs = properties.getJobs();
    if (jobs.isEmpty()) {
      // Almost always means schedules.yml did not load — a profile whose spring.config.import
      // omits it, or a renamed file. Nothing is scheduled, and the service otherwise looks healthy,
      // so this is logged at error rather than left as a quiet warning.
      log.error(
          "event=schedule_registration_empty reason=no_jobs_declared "
              + "check=spring.config.import_includes_schedules.yml");
      LoggingUtils.logAuditEvent(
          "SCHEDULE_CONFIG_EMPTY", "REGISTRAR", "schedules.yml declared no jobs");
      return;
    }

    Set<String> seen = new HashSet<>();
    int registered = 0;
    int invalid = 0;
    int failed = 0;

    for (JobDefinition job : jobs) {
      Class<?> workflowType = resolveWorkflow(job.getWorkflow());
      List<String> problems = validate(job, seen, workflowType);
      if (!problems.isEmpty()) {
        invalid++;
        log.error(
            "event=schedule_invalid job={} problems={}",
            Objects.toString(job.getName(), "<unnamed>"),
            problems);
        LoggingUtils.logAuditEvent(
            "SCHEDULE_CONFIG_INVALID",
            Objects.toString(job.getName(), "<unnamed>"),
            String.join("; ", problems));
        continue;
      }

      try {
        registrar.createIfAbsent(toRequest(job, workflowType));
        registered++;
      } catch (RuntimeException ex) {
        failed++;
        log.error(
            "event=schedule_create_failed job={} error={}", job.getName(), ex.getMessage(), ex);
      }
    }

    log.info(
        "event=schedule_registration_complete declared={} registered={} invalid={} failed={}",
        jobs.size(),
        registered,
        invalid,
        failed);

    reportOrphans(jobs);
  }

  /**
   * Validates one definition, returning what is wrong with it rather than throwing.
   *
   * <p>Per job rather than per file on purpose. Aborting the whole run on the first bad entry means
   * one typo in one job's class name stops the other hundred from picking up their changes — the
   * blast radius of a mistake should be the job that contains it.
   *
   * @param seen names already encountered, mutated to detect duplicates
   * @return the problems found, empty if the job is usable
   */
  private List<String> validate(JobDefinition job, Set<String> seen, Class<?> workflowType) {
    List<String> problems = new ArrayList<>();

    String name = job.getName();
    if (!StringUtils.hasText(name)) {
      return List.of("missing 'name'");
    }
    if (!seen.add(name)) {
      problems.add("duplicate job name");
    }
    if (!StringUtils.hasText(job.getCron())) {
      problems.add("missing 'cron'");
    } else {
      problems.addAll(cronProblems(job.getCron()));
    }
    if (Objects.isNull(job.getTier())) {
      // An unknown tier never reaches here — Spring fails the bind. Only an omitted one can.
      problems.add("missing 'tier'");
    }

    if (!StringUtils.hasText(job.getWorkflow())) {
      problems.add("missing 'workflow'");
    } else if (Objects.isNull(workflowType)) {
      problems.add("workflow class not found — " + job.getWorkflow());
    } else if (Objects.isNull(workflowType.getAnnotation(WorkflowInterface.class))) {
      // Pointing at the impl instead of the interface resolves fine and fails much later inside
      // setWorkflowType, where the message says nothing about which job caused it.
      problems.add(
          "'workflow' must be the @WorkflowInterface, not an implementation — "
              + job.getWorkflow());
    }

    return problems;
  }

  /**
   * Checks the cron is Temporal's five-field form rather than Spring's six-field one.
   *
   * <p>Every job being migrated in is written in Spring's dialect — a leading seconds field, and
   * {@code ?} where standard cron wants {@code *}. Pasting one across is the most likely mistake
   * anyone will make with this file, and it is not reliably loud: a six-field expression can parse
   * into a valid but different schedule, so the job runs at the wrong time rather than failing.
   */
  private static List<String> cronProblems(String cron) {
    List<String> problems = new ArrayList<>();
    String[] fields = cron.trim().split("\\s+");
    if (fields.length != 5) {
      problems.add(
          String.format(
              "cron '%s' has %d fields; Temporal expects 5 (minute hour day-of-month month "
                  + "day-of-week). Spring's 6-field form with leading seconds does not port",
              cron, fields.length));
    }
    if (cron.contains("?")) {
      problems.add("cron '" + cron + "' uses '?', which is Quartz-only — use '*'");
    }
    return problems;
  }

  private CronScheduleRequest toRequest(JobDefinition job, Class<?> workflowType) {
    return CronScheduleRequest.builder()
        .scheduleId(job.scheduleId())
        .workflowId(job.workflowId())
        .workflowType(workflowType)
        .taskQueue(queueByTier.get(job.getTier()))
        .cronExpression(job.getCron())
        .timeZone(job.getTimeZone())
        .overlapPolicy(job.getOverlap())
        .catchupWindow(job.getCatchupWindow())
        .jitter(job.getJitter())
        .runTimeout(job.getRunTimeout())
        .pauseOnFailure(job.getPauseOnFailure())
        .createPaused(!job.isEnabled())
        .build();
  }

  private static Class<?> resolveWorkflow(String className) {
    if (!StringUtils.hasText(className)) {
      return null;
    }
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException ex) {
      return null;
    }
  }

  /**
   * Reports schedules this service created that are no longer declared.
   *
   * <p>Reported, never removed. A schedule disappearing from the YAML is far more often an editing
   * mistake than an intentional decommission, and the cost of the two errors is not symmetric.
   */
  private void reportOrphans(List<JobDefinition> jobs) {
    Set<String> declared = jobs.stream().map(JobDefinition::scheduleId).collect(Collectors.toSet());

    try (var stream = scheduleClient.listSchedules()) {
      List<String> orphans =
          stream
              .filter(this::isManagedByUs)
              .map(ScheduleListDescription::getScheduleId)
              .filter(id -> !declared.contains(id))
              .collect(Collectors.toList());

      if (!orphans.isEmpty()) {
        log.warn(
            "event=schedule_orphans_found count={} schedule_ids={} "
                + "action=none_taken_remove_manually_if_intended",
            orphans.size(),
            orphans);
        LoggingUtils.logAuditEvent(
            "SCHEDULE_ORPHANS_FOUND", "RECONCILER", String.join(",", orphans));
      }
    } catch (RuntimeException ex) {
      log.warn("event=schedule_orphan_check_failed error={}", ex.getMessage());
    }
  }

  private boolean isManagedByUs(ScheduleListDescription description) {
    try {
      Object memo =
          description.getMemo(TemporalScheduleRegistrar.MANAGED_BY_MEMO_KEY, String.class);
      return Objects.equals(memo, TemporalScheduleRegistrar.MANAGED_BY_MEMO_VALUE);
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
