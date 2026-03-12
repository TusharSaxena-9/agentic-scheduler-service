package com.yesmadam.scheduler_service.exception.handler;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.yesmadam.scheduler_service.exception.dto.ErrorInfo;
import com.yesmadam.scheduler_service.logging.LoggingUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for the scheduler service. Provides centralized error handling, logging,
 * and monitoring for non-REST applications.
 */
@Slf4j
@Component
public class GlobalExceptionHandler {

  /**
   * Handles database access exceptions. These typically occur when there are issues with database
   * connectivity or queries.
   *
   * @return RuntimeException wrapping the original DataAccessException
   */
  public RuntimeException handleDataAccessException(DataAccessException ex) {
    String traceId = LoggingUtils.getCorrelationId();
    ErrorInfo errorInfo =
        ErrorInfo.builder()
            .errorType("DATABASE_ERROR")
            .message("Database operation failed")
            .traceId(traceId)
            .component("DATABASE")
            .build();

    log.error(
        "event=error error_type={} trace_id={} error={}",
        errorInfo.getErrorType(),
        traceId,
        ex.getMessage(),
        ex);
    LoggingUtils.logAuditEvent(
        "DATABASE_ERROR", traceId, String.format("error=%s", ex.getMessage()));
    logMetrics(errorInfo);

    return new RuntimeException("Database operation failed", ex);
  }

  /** Handles SQL exceptions. These occur for specific SQL-related errors. */
  public void handleSQLException(SQLException ex) {
    String traceId = LoggingUtils.getCorrelationId();
    ErrorInfo errorInfo =
        ErrorInfo.builder()
            .errorType("SQL_ERROR")
            .message("SQL operation failed")
            .traceId(traceId)
            .component("DATABASE")
            .build();

    log.error(
        "event=error error_type={} trace_id={} error={}",
        errorInfo.getErrorType(),
        traceId,
        ex.getMessage(),
        ex);
    LoggingUtils.logAuditEvent("SQL_ERROR", traceId, String.format("error=%s", ex.getMessage()));
    logMetrics(errorInfo);
  }

  /**
   * Handles constraint violation exceptions. These occur when bean validation constraints are
   * violated.
   */
  public void handleConstraintViolationException(ConstraintViolationException ex) {
    String traceId = LoggingUtils.getCorrelationId();
    Map<String, String> errors = new HashMap<>();

    Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
    for (ConstraintViolation<?> violation : violations) {
      errors.put(violation.getPropertyPath().toString(), violation.getMessage());
    }

    ErrorInfo errorInfo =
        ErrorInfo.builder()
            .errorType("CONSTRAINT_VIOLATION")
            .message("Request constraints violated")
            .traceId(traceId)
            .component("VALIDATION")
            .details(errors.toString())
            .build();

    log.warn(
        "event=error error_type={} trace_id={} violations={}",
        errorInfo.getErrorType(),
        traceId,
        errors);
    LoggingUtils.logAuditEvent(
        "CONSTRAINT_VIOLATION", traceId, String.format("violations=%s", errors));
    logMetrics(errorInfo);
  }

  /**
   * Handles illegal argument exceptions. These occur when invalid arguments are passed to methods.
   */
  public void handleIllegalArgumentException(IllegalArgumentException ex) {
    String traceId = LoggingUtils.getCorrelationId();
    ErrorInfo errorInfo =
        ErrorInfo.builder()
            .errorType("ILLEGAL_ARGUMENT")
            .message(ex.getMessage())
            .traceId(traceId)
            .component("BUSINESS_LOGIC")
            .build();

    log.warn(
        "event=error error_type={} trace_id={} error={}",
        errorInfo.getErrorType(),
        traceId,
        ex.getMessage(),
        ex);
    LoggingUtils.logAuditEvent(
        "ILLEGAL_ARGUMENT", traceId, String.format("error=%s", ex.getMessage()));
    logMetrics(errorInfo);
  }

  /** Handles illegal state exceptions. These occur when the application is in an invalid state. */
  public void handleIllegalStateException(IllegalStateException ex) {
    String traceId = LoggingUtils.getCorrelationId();
    ErrorInfo errorInfo =
        ErrorInfo.builder()
            .errorType("ILLEGAL_STATE")
            .message("Application is in an invalid state")
            .traceId(traceId)
            .component("APPLICATION")
            .details(ex.getMessage())
            .build();

    log.error(
        "event=error error_type={} trace_id={} error={}",
        errorInfo.getErrorType(),
        traceId,
        ex.getMessage(),
        ex);
    LoggingUtils.logAuditEvent(
        "ILLEGAL_STATE", traceId, String.format("error=%s", ex.getMessage()));
    logMetrics(errorInfo);
  }

  /** Handles runtime exceptions. This is a catch-all for all other runtime exceptions. */
  public void handleRuntimeException(RuntimeException ex) {
    String traceId = LoggingUtils.getCorrelationId();
    ErrorInfo errorInfo =
        ErrorInfo.builder()
            .errorType("RUNTIME_ERROR")
            .message("An unexpected error occurred")
            .traceId(traceId)
            .component("APPLICATION")
            .details(ex.getMessage())
            .build();

    log.error(
        "event=error error_type={} trace_id={} error={}",
        errorInfo.getErrorType(),
        traceId,
        ex.getMessage(),
        ex);
    LoggingUtils.logAuditEvent(
        "RUNTIME_ERROR", traceId, String.format("error=%s", ex.getMessage()));
    logMetrics(errorInfo);
  }

  /** Handles all other exceptions. This is the final catch-all for any unhandled exceptions. */
  public void handleGenericException(Exception ex) {
    String traceId = LoggingUtils.getCorrelationId();
    ErrorInfo errorInfo =
        ErrorInfo.builder()
            .errorType("UNEXPECTED_ERROR")
            .message("An unexpected error occurred")
            .traceId(traceId)
            .component("APPLICATION")
            .details(ex.getMessage())
            .build();

    log.error(
        "event=error error_type={} trace_id={} error={}",
        errorInfo.getErrorType(),
        traceId,
        ex.getMessage(),
        ex);
    LoggingUtils.logAuditEvent(
        "UNEXPECTED_ERROR", traceId, String.format("error=%s", ex.getMessage()));
    logMetrics(errorInfo);
  }

  /**
   * Logs error metrics for monitoring. This can be extended to send metrics to monitoring systems.
   *
   * @param errorInfo the error information to log metrics for
   */
  private void logMetrics(ErrorInfo errorInfo) {
    LoggingUtils.logPerformanceMetric("error-count", 1, "occurrences");
    LoggingUtils.logPerformanceMetric(
        String.format("error-%s", errorInfo.getErrorType().toLowerCase()), 1, "occurrences");
    log.info(
        "event=error_metric error_type={} component={} trace_id={}",
        errorInfo.getErrorType(),
        errorInfo.getComponent(),
        errorInfo.getTraceId());
  }
}
