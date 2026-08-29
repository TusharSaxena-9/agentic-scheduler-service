package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds {@code schedules.yml} exactly as Spring would and checks the result.
 *
 * <p>Two failure modes here are silent rather than loud, which is why they are worth a test.
 *
 * <p>A misspelled property key does not fail — relaxed binding simply leaves the field null, and the
 * job quietly inherits a default instead of the policy someone wrote down. And a schedule ID that
 * does not match the one already live in Temporal does not fail either: the reconciler creates a
 * second schedule alongside the original, so the job runs twice and the old schedule becomes an
 * orphan nobody is looking for.
 */
class SchedulesYamlTest {

  private static JobScheduleProperties properties;

  @BeforeAll
  static void bindYaml() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load("schedules", new ClassPathResource("schedules.yml"));
    sources.forEach(source -> environment.getPropertySources().addLast(source));

    properties =
        Binder.get(environment)
            .bind("scheduler", JobScheduleProperties.class)
            .orElseThrow(() -> new AssertionError("scheduler.* did not bind from schedules.yml"));
  }

  @Test
  @DisplayName("every declared job binds")
  void allJobsBind() {
    assertThat(properties.getJobs()).isNotEmpty();
    assertThat(properties.isReconcileOnStartup()).isTrue();
    assertThat(properties.getJobs()).allSatisfy(job -> {
      assertThat(job.getName()).isNotBlank();
      assertThat(job.getCron()).isNotBlank();
      assertThat(job.getTier()).isNotNull();
      assertThat(job.getWorkflow()).isNotBlank();
      assertThat(job.getOwner()).isNotBlank();
    });
  }

  @Test
  @DisplayName("derived schedule IDs match the schedules already live in Temporal")
  void scheduleIdsMatchExistingProductionSchedules() {
    assertThat(properties.getJobs())
        .extracting(JobDefinition::scheduleId)
        // Subset, not exact: this pins the five that already exist in Temporal so a rename cannot
        // silently orphan them, without failing every time a new job is added.
        .contains(
            "package-expiry-schedule",
            "booking-monitor-schedule",
            "rose-order-eligible-sp-pool-schedule",
            "sebal-tagging-schedule",
            "passbook-mismatch-schedule");
  }

  @Test
  @DisplayName("derived workflow IDs match the ones already in use")
  void workflowIdsMatchExisting() {
    assertThat(properties.getJobs())
        .extracting(JobDefinition::workflowId)
        .contains(
            "package-expiry-workflow",
            "booking-monitor-workflow",
            "rose-order-eligible-sp-pool-workflow",
            "sebal-tagging-workflow",
            "passbook-mismatch-workflow");
  }

  @Test
  @DisplayName("every declared workflow class exists and is a workflow interface")
  void workflowClassesResolve() {
    assertThat(properties.getJobs())
        .allSatisfy(
            job -> {
              Class<?> type = Class.forName(job.getWorkflow());
              assertThat(type.isInterface())
                  .as("%s should be the @WorkflowInterface, not the impl", job.getName())
                  .isTrue();
            });
  }

  @Test
  @DisplayName("every tier binds to the enum — an unknown value would have failed the bind above")
  void tiersAreKnown() {
    assertThat(properties.getJobs()).extracting(JobDefinition::getTier).doesNotContainNull();
  }

  @Test
  @DisplayName("an unknown tier fails at binding rather than reaching the reconciler")
  void unknownTierFailsToBind() {
    StandardEnvironment environment = new StandardEnvironment();
    Map<String, Object> bad =
        Map.of(
            "scheduler.jobs[0].name", "made-up",
            "scheduler.jobs[0].workflow", "java.lang.Runnable",
            "scheduler.jobs[0].cron", "0 1 * * *",
            "scheduler.jobs[0].tier", "gigantic");
    environment.getPropertySources().addLast(new MapPropertySource("bad", bad));

    assertThatThrownBy(
            () -> Binder.get(environment).bind("scheduler", JobScheduleProperties.class))
        .isInstanceOf(BindException.class);
  }

  @Test
  @DisplayName("hyphenated policy keys actually bind — a misspelling would silently be null")
  void policyOverridesBind() {
    JobDefinition passbook = job("passbook-mismatch");
    assertThat(passbook.getRunTimeout()).isNotNull();
    assertThat(passbook.getCatchupWindow()).isNotNull();
    assertThat(passbook.getPauseOnFailure()).isTrue();

    JobDefinition monitor = job("booking-monitor");
    assertThat(monitor.getJitter()).isZero();
  }

  @Test
  @DisplayName("crons are five-field Temporal format, not six-field Spring format")
  void cronsAreTemporalFormat() {
    assertThat(properties.getJobs())
        .allSatisfy(
            job -> {
              assertThat(job.getCron().trim().split("\\s+"))
                  .as("%s cron '%s' must be 5-field", job.getName(), job.getCron())
                  .hasSize(5);
              assertThat(job.getCron())
                  .as("%s cron must not use the Quartz-only '?'", job.getName())
                  .doesNotContain("?");
            });
  }

  @Test
  @DisplayName("an omitted timeZone stays null so the registrar default applies")
  void omittedTimeZoneInheritsDefault() {
    // Every job here omits it. Setting it in the reconciler instead would bypass
    // temporal.schedule.defaults.time-zone and pin the value in two places.
    assertThat(properties.getJobs()).allSatisfy(job -> assertThat(job.getTimeZone()).isNull());
  }

  @Test
  @DisplayName("nothing is enabled by default — jobs are turned on deliberately")
  void nothingEnabledByDefault() {
    assertThat(properties.getJobs()).noneMatch(JobDefinition::isEnabled);
  }

  private static JobDefinition job(String name) {
    return properties.getJobs().stream()
        .filter(j -> name.equals(j.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no job named " + name));
  }
}
