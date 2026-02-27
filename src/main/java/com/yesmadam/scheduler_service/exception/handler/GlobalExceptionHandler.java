package com.yesmadam.scheduler_service.exception.handler;

import com.yesmadam.scheduler_service.exception.dto.ErrorInfo;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Global exception handler for the scheduler service.
 * Provides centralized error handling, logging, and monitoring for non-REST applications.
 */
@Slf4j
@Component
public class GlobalExceptionHandler {

    /**
     * Handles database access exceptions.
     * These typically occur when there are issues with database connectivity or queries.
     */
    public void handleDataAccessException(DataAccessException ex) {
        String traceId = LoggingUtils.getCorrelationId();
        ErrorInfo errorInfo = new ErrorInfo(
            "DATABASE_ERROR",
            "Database operation failed",
            traceId,
            "DATABASE"
        );
        
        log.error("Database error [traceId={}]: {}", traceId, ex.getMessage(), ex);
        LoggingUtils.logAuditEvent("DATABASE_ERROR", traceId, 
            String.format("Database operation failed: %s", ex.getMessage()));
        
        // Could send to monitoring system here
        logMetrics(errorInfo);
    }

    /**
     * Handles SQL exceptions.
     * These occur for specific SQL-related errors.
     */
    public void handleSQLException(SQLException ex) {
        String traceId = LoggingUtils.getCorrelationId();
        ErrorInfo errorInfo = new ErrorInfo(
            "SQL_ERROR",
            "SQL operation failed",
            traceId,
            "DATABASE"
        );
        
        log.error("SQL error [traceId={}]: {}", traceId, ex.getMessage(), ex);
        LoggingUtils.logAuditEvent("SQL_ERROR", traceId, 
            String.format("SQL operation failed: %s", ex.getMessage()));
        
        // Could send to monitoring system here
        logMetrics(errorInfo);
    }

    /**
     * Handles constraint violation exceptions.
     * These occur when bean validation constraints are violated.
     */
    public void handleConstraintViolationException(ConstraintViolationException ex) {
        String traceId = LoggingUtils.getCorrelationId();
        Map<String, String> errors = new HashMap<>();
        
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        for (ConstraintViolation<?> violation : violations) {
            String fieldName = violation.getPropertyPath().toString();
            String errorMessage = violation.getMessage();
            errors.put(fieldName, errorMessage);
        }
        
        ErrorInfo errorInfo = new ErrorInfo(
            "CONSTRAINT_VIOLATION",
            "Request constraints violated",
            traceId,
            "VALIDATION",
            errors.toString()
        );
        
        log.warn("Constraint violation [traceId={}]: {}", traceId, errors);
        LoggingUtils.logAuditEvent("CONSTRAINT_VIOLATION", traceId, 
            String.format("Constraint violation: %s", errors));
        
        // Could send to monitoring system here
        logMetrics(errorInfo);
    }

    /**
     * Handles illegal argument exceptions.
     * These occur when invalid arguments are passed to methods.
     */
    public void handleIllegalArgumentException(IllegalArgumentException ex) {
        String traceId = LoggingUtils.getCorrelationId();
        ErrorInfo errorInfo = new ErrorInfo(
            "ILLEGAL_ARGUMENT",
            ex.getMessage(),
            traceId,
            "BUSINESS_LOGIC"
        );
        
        log.warn("Illegal argument [traceId={}]: {}", traceId, ex.getMessage(), ex);
        LoggingUtils.logAuditEvent("ILLEGAL_ARGUMENT", traceId, 
            String.format("Illegal argument: %s", ex.getMessage()));
        
        // Could send to monitoring system here
        logMetrics(errorInfo);
    }

    /**
     * Handles illegal state exceptions.
     * These occur when the application is in an invalid state.
     */
    public void handleIllegalStateException(IllegalStateException ex) {
        String traceId = LoggingUtils.getCorrelationId();
        ErrorInfo errorInfo = new ErrorInfo(
            "ILLEGAL_STATE",
            "Application is in an invalid state",
            traceId,
            "APPLICATION",
            ex.getMessage()
        );
        
        log.error("Illegal state [traceId={}]: {}", traceId, ex.getMessage(), ex);
        LoggingUtils.logAuditEvent("ILLEGAL_STATE", traceId, 
            String.format("Illegal state: %s", ex.getMessage()));
        
        // Could send to monitoring system here
        logMetrics(errorInfo);
    }

    /**
     * Handles runtime exceptions.
     * This is a catch-all for all other runtime exceptions.
     */
    public void handleRuntimeException(RuntimeException ex) {
        String traceId = LoggingUtils.getCorrelationId();
        ErrorInfo errorInfo = new ErrorInfo(
            "RUNTIME_ERROR",
            "An unexpected error occurred",
            traceId,
            "APPLICATION",
            ex.getMessage()
        );
        
        log.error("Runtime error [traceId={}]: {}", traceId, ex.getMessage(), ex);
        LoggingUtils.logAuditEvent("RUNTIME_ERROR", traceId, 
            String.format("Runtime error: %s", ex.getMessage()));
        
        // Could send to monitoring system here
        logMetrics(errorInfo);
    }

    /**
     * Handles all other exceptions.
     * This is the final catch-all for any unhandled exceptions.
     */
    public void handleGenericException(Exception ex) {
        String traceId = LoggingUtils.getCorrelationId();
        ErrorInfo errorInfo = new ErrorInfo(
            "UNEXPECTED_ERROR",
            "An unexpected error occurred",
            traceId,
            "APPLICATION",
            ex.getMessage()
        );
        
        log.error("Unexpected error [traceId={}]: {}", traceId, ex.getMessage(), ex);
        LoggingUtils.logAuditEvent("UNEXPECTED_ERROR", traceId, 
            String.format("Unexpected error: %s", ex.getMessage()));
        
        // Could send to monitoring system here
        logMetrics(errorInfo);
    }
    
    /**
     * Logs error metrics for monitoring.
     * This can be extended to send metrics to monitoring systems.
     * @param errorInfo the error information to log metrics for
     */
    private void logMetrics(ErrorInfo errorInfo) {
        // Log metrics for monitoring systems
        LoggingUtils.logPerformanceMetric("error-count", 1, "occurrences");
        LoggingUtils.logPerformanceMetric(
            String.format("error-%s", errorInfo.getErrorType().toLowerCase()), 
            1, 
            "occurrences"
        );
        
        // Could integrate with Micrometer, Prometheus, etc. here
        log.info("Error metrics [type={}, component={}, traceId={}]", 
            errorInfo.getErrorType(), errorInfo.getComponent(), errorInfo.getTraceId());
    }
}
