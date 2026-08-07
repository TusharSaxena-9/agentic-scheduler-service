package com.yesmadam.schedulerservice.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.logging.LoggingUtils;

import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Emits the WORKERS_STARTED audit event once the Temporal Spring Boot starter has started the
 * {@link WorkerFactory}.
 *
 * <p>The starter starts workers from its own {@code ApplicationReadyEvent} listener; this listener
 * runs at lowest precedence so it observes the factory state afterwards. It replaces the audit
 * logging previously done by the manual worker-startup {@code ApplicationRunner}.
 */
@Slf4j
@Component
public class TemporalWorkerAuditLogger
    implements ApplicationListener<ApplicationReadyEvent>, Ordered {

  private final WorkerFactory workerFactory;

  public TemporalWorkerAuditLogger(WorkerFactory workerFactory) {
    this.workerFactory = workerFactory;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    try {
      if (workerFactory.isStarted()) {
        LoggingUtils.logAuditEvent(
            "WORKERS_STARTED", "TEMPORAL", "All Temporal workers started successfully");
      } else {
        LoggingUtils.logAuditEvent(
            "WORKERS_NOT_STARTED", "TEMPORAL", "WorkerFactory not started at application ready");
      }
    } finally {
      LoggingUtils.clearContext();
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
