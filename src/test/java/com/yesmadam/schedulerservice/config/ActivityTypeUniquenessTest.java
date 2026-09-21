package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.spring.boot.ActivityImpl;

/**
 * Fails the build when two activity methods sharing a task queue would register under the same
 * Temporal activity type.
 *
 * <p>An activity type name is global to the <b>worker</b>, not scoped to the interface that
 * declares it. Two jobs on the same tier, each with a method of the same name — a {@code
 * resolveRunWindow} or a {@code recordOutcome} apiece — therefore collide, and the collision is not
 * a compile error, not a test failure, and not confined to those jobs. It surfaces as {@code
 * TypeAlreadyRegisteredException} while the worker is being built, which fails the whole Spring
 * context: the service will not start at all, and every other job on every other tier stops with
 * it.
 *
 * <p>That is a bad way to find out, and review is a poor defence because the two files need never
 * mention each other. This test is the mechanical check — rule 19's argument applied to a second
 * class of registration bug.
 *
 * <p>The convention that satisfies it is to name every activity type explicitly with
 * {@code @ActivityMethod(name = ...)}, prefixed by the job, rather than letting it default to the
 * capitalised method name.
 */
class ActivityTypeUniquenessTest {

  private static final String JOBS_PACKAGE = "com.yesmadam.schedulerservice.jobs";

  /** Task queue placeholder → activity type name → the methods registering it. */
  private static final Map<String, Map<String, List<String>>> typesByQueue = new TreeMap<>();

  @BeforeAll
  static void scanActivityImplementations() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(ActivityImpl.class));

    for (BeanDefinition definition : scanner.findCandidateComponents(JOBS_PACKAGE)) {
      Class<?> impl = Class.forName(definition.getBeanClassName());
      String[] taskQueues = impl.getAnnotation(ActivityImpl.class).taskQueues();

      for (String queue : taskQueues) {
        Map<String, List<String>> types =
            typesByQueue.computeIfAbsent(queue, key -> new LinkedHashMap<>());

        for (Class<?> iface : impl.getInterfaces()) {
          if (iface.getAnnotation(ActivityInterface.class) == null) {
            continue;
          }
          for (Method method : iface.getDeclaredMethods()) {
            if (method.getAnnotation(ActivityMethod.class) == null) {
              continue;
            }
            types
                .computeIfAbsent(activityTypeName(method), key -> new ArrayList<>())
                .add(iface.getSimpleName() + "#" + method.getName());
          }
        }
      }
    }
  }

  /** The name Temporal registers: the explicit one, else the method name capitalised. */
  private static String activityTypeName(Method method) {
    String declared = method.getAnnotation(ActivityMethod.class).name();
    if (!declared.isEmpty()) {
      return declared;
    }
    String name = method.getName();
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  @Test
  @DisplayName("the scan found activity implementations — otherwise this test passes vacuously")
  void implementationsWereFound() {
    assertThat(typesByQueue)
        .as("no @ActivityImpl classes found under %s", JOBS_PACKAGE)
        .isNotEmpty();
    assertThat(typesByQueue.values().stream().mapToInt(Map::size).sum()).isGreaterThan(5);
  }

  @Test
  @DisplayName("no two activity methods on one task queue share an activity type name")
  void activityTypeNamesAreUniquePerTaskQueue() {
    Map<String, List<String>> clashes = new LinkedHashMap<>();

    typesByQueue.forEach(
        (queue, types) ->
            types.forEach(
                (type, methods) -> {
                  if (methods.size() > 1) {
                    clashes.put(queue + " / " + type, methods);
                  }
                }));

    assertThat(clashes)
        .as(
            "Activity type names are global to a worker, so these would throw "
                + "TypeAlreadyRegisteredException at startup and stop the whole service booting. "
                + "Give each an explicit @ActivityMethod(name = ...) prefixed by its job.")
        .isEmpty();
  }
}
