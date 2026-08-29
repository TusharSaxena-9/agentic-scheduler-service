package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;

/**
 * Binds {@code spring.temporal.workers[*]} exactly as Spring would and checks the result.
 *
 * <p>This exists because the failure mode is silence. A misspelled key under {@code capacity.*} does
 * not fail to start — it simply never binds, the worker is created on SDK defaults of 200 activity
 * slots, and the first symptom is a shared database taking load nobody intended. The same is true if
 * a worker block is dropped entirely: the task queue is still referenced by an annotation, so the
 * starter creates a worker for it with default configuration rather than complaining.
 *
 * <p>It also pins the task queue placeholders. Declaring the queue name literally here would let a
 * rename in {@code temporal.taskqueue.*} leave capacity attached to a queue no worker polls.
 */
class WorkerCapacityConfigTest {

  private static List<WorkerProperties> workers;

  @BeforeAll
  static void bindWorkers() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addLast(
            new ResourcePropertySource(
                "application", new ClassPathResource("application.properties")));

    workers =
        Binder.get(environment)
            .bind("spring.temporal.workers", Bindable.listOf(WorkerProperties.class))
            .orElseThrow(() -> new AssertionError("spring.temporal.workers did not bind"));
  }

  private static WorkerProperties worker(String taskQueue) {
    return workers.stream()
        .filter(w -> taskQueue.equals(w.getTaskQueue()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no worker declared for task queue " + taskQueue));
  }

  @Test
  @DisplayName("all four tiers declare a worker — an undeclared tier silently gets SDK defaults")
  void allTiersDeclared() {
    assertThat(workers).hasSize(4);
    assertThat(workers)
        .extracting(WorkerProperties::getTaskQueue)
        .containsExactlyInAnyOrder(
            "scheduler-light", "scheduler-standard", "scheduler-heavy", "scheduler-notification");
  }

  @Test
  @DisplayName("task queue placeholders resolve rather than being hard-coded")
  void taskQueuePlaceholdersResolve() {
    assertThat(workers)
        .extracting(WorkerProperties::getTaskQueue)
        .allSatisfy(queue -> assertThat(queue).doesNotContain("${"));
  }

  @Test
  @DisplayName("heavy is capped far below the SDK default of 200 activity executors")
  void heavyTierIsCapped() {
    WorkerProperties heavy = worker("scheduler-heavy");

    assertThat(heavy.getCapacity()).isNotNull();
    assertThat(heavy.getCapacity().getMaxConcurrentActivityExecutors()).isEqualTo(6);
    assertThat(heavy.getCapacity().getMaxConcurrentWorkflowTaskExecutors()).isEqualTo(20);
    assertThat(heavy.getCapacity().getMaxConcurrentActivityTaskPollers()).isEqualTo(2);
    assertThat(heavy.getCapacity().getMaxConcurrentWorkflowTaskPollers()).isEqualTo(2);
  }

  @Test
  @DisplayName("each tier gets its own activity executor count")
  void eachTierIsDistinct() {
    assertThat(worker("scheduler-light").getCapacity().getMaxConcurrentActivityExecutors())
        .isEqualTo(100);
    assertThat(worker("scheduler-standard").getCapacity().getMaxConcurrentActivityExecutors())
        .isEqualTo(40);
    assertThat(worker("scheduler-heavy").getCapacity().getMaxConcurrentActivityExecutors())
        .isEqualTo(6);
    assertThat(worker("scheduler-notification").getCapacity().getMaxConcurrentActivityExecutors())
        .isEqualTo(20);
  }

  @Test
  @DisplayName("every capacity key binds — a misspelling would leave it null and default to 200")
  void everyCapacityKeyBinds() {
    assertThat(workers)
        .allSatisfy(
            w -> {
              assertThat(w.getCapacity())
                  .as("%s has no capacity block", w.getTaskQueue())
                  .isNotNull();
              assertThat(w.getCapacity().getMaxConcurrentActivityExecutors()).isNotNull();
              assertThat(w.getCapacity().getMaxConcurrentWorkflowTaskExecutors()).isNotNull();
              assertThat(w.getCapacity().getMaxConcurrentActivityTaskPollers()).isNotNull();
              assertThat(w.getCapacity().getMaxConcurrentWorkflowTaskPollers()).isNotNull();
            });
  }

  @Test
  @DisplayName("total activity executors per pod stay within reach of the writer pool")
  void totalActivityExecutorsAreBounded() {
    int total =
        workers.stream()
            .mapToInt(w -> w.getCapacity().getMaxConcurrentActivityExecutors())
            .sum();

    // 4 workers on SDK defaults would be 800. The ceiling here is a deliberate figure, not an
    // accident — if it grows, the shared database budget has to grow with it.
    assertThat(total).isLessThanOrEqualTo(200);
  }
}
