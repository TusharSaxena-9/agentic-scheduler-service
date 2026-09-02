package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import io.temporal.spring.boot.WorkflowImpl;

/**
 * Checks that the two places a job's task queue is decided actually agree.
 *
 * <p>A task queue is a rendezvous between two parties that know nothing about each other: the
 * schedule says where to put the workflow's tasks, and {@code @WorkflowImpl} says where a worker
 * polls and which types it serves. Temporal joins them by string alone, and does not complain when
 * they differ.
 *
 * <p>So declaring {@code tier: heavy} for a workflow annotated for the light queue produces a
 * schedule that dispatches somewhere the workflow type is not registered. There is no error and no
 * failed workflow — the task simply sits in a queue nobody serves it from, which looks identical to
 * a job that is merely idle. This test turns that into a build failure.
 */
class WorkflowTierAgreementTest {

  private static final String JOBS_PACKAGE = "com.yesmadam.schedulerservice.jobs";

  private static List<JobDefinition> jobs;

  /** Workflow interface to the task queue placeholder its implementation is annotated with. */
  private static final Map<Class<?>, String> queueByWorkflowInterface = new LinkedHashMap<>();

  @BeforeAll
  static void loadJobsAndImplementations() throws IOException, ClassNotFoundException {
    StandardEnvironment environment = new StandardEnvironment();
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load("schedules", new ClassPathResource("schedules.yml"));
    sources.forEach(source -> environment.getPropertySources().addLast(source));
    jobs =
        Binder.get(environment)
            .bind("scheduler", JobScheduleProperties.class)
            .orElseThrow(() -> new AssertionError("schedules.yml did not bind"))
            .getJobs();

    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(WorkflowImpl.class));

    for (BeanDefinition definition : scanner.findCandidateComponents(JOBS_PACKAGE)) {
      Class<?> impl = Class.forName(definition.getBeanClassName());
      String[] taskQueues = impl.getAnnotation(WorkflowImpl.class).taskQueues();
      if (taskQueues.length == 0) {
        continue;
      }
      for (Class<?> iface : impl.getInterfaces()) {
        queueByWorkflowInterface.put(iface, taskQueues[0]);
      }
    }
  }

  @Test
  @DisplayName("the scan found the workflow implementations it was looking for")
  void implementationsWereFound() {
    assertThat(queueByWorkflowInterface)
        .as(
            "no @WorkflowImpl classes found under %s — this test would pass vacuously",
            JOBS_PACKAGE)
        .isNotEmpty();
  }

  @Test
  @DisplayName("each job's declared tier matches the queue its workflow is annotated for")
  void declaredTierMatchesAnnotation() {
    assertThat(jobs)
        .allSatisfy(
            job -> {
              Class<?> workflowInterface = Class.forName(job.getWorkflow());
              String annotated = queueByWorkflowInterface.get(workflowInterface);

              assertThat(annotated)
                  .as("no @WorkflowImpl implements %s", job.getWorkflow())
                  .isNotNull();

              String expected = "${temporal.taskqueue." + tierProperty(job) + "}";
              assertThat(annotated)
                  .as(
                      "job '%s' declares tier %s but %s is annotated for %s",
                      job.getName(), job.getTier(), workflowInterface.getSimpleName(), annotated)
                  .isEqualTo(expected);
            });
  }

  private static String tierProperty(JobDefinition job) {
    return Objects.requireNonNull(job.getTier(), "tier").name().toLowerCase();
  }
}
