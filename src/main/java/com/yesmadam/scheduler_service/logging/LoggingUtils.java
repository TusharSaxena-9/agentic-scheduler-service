package com.yesmadam.scheduler_service.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;
import java.util.UUID;

/**
 * Utility class for structured logging with correlation IDs.
 * Provides methods to manage MDC context and structured logging.
 */
public class LoggingUtils {

    private static final Logger log = LoggerFactory.getLogger(LoggingUtils.class);

    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String WORKFLOW_ID_KEY = "workflowId";
    private static final String ACTIVITY_KEY = "activity";
    private static final String UNKNOWN = "unknown";

    private LoggingUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Sets a correlation ID in the MDC context.
     * @param correlationId the correlation ID to set, generates one if null
     * @return the correlation ID that was set
     */
    public static String setCorrelationId(String correlationId) {
        String id = Objects.nonNull(correlationId) ? correlationId : UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID_KEY, id);
        return id;
    }

    /**
     * Sets an externally provided correlation ID (e.g. from upstream service headers).
     * Falls back to generating a new one if not provided.
     * @param externalId the correlation ID from an upstream service
     * @return the correlation ID that was set
     */
    public static String setCorrelationIdFromUpstream(String externalId) {
        return setCorrelationId(externalId);
    }

    /**
     * Sets workflow ID in the MDC context.
     * @param workflowId the workflow ID to set
     */
    public static void setWorkflowId(String workflowId) {
        if (Objects.nonNull(workflowId)) {
            MDC.put(WORKFLOW_ID_KEY, workflowId);
        }
    }

    /**
     * Sets activity name in the MDC context.
     * @param activityName the activity name to set
     */
    public static void setActivity(String activityName) {
        if (Objects.nonNull(activityName)) {
            MDC.put(ACTIVITY_KEY, activityName);
        }
    }

    /**
     * Clears the MDC context. Always call this in a finally block.
     */
    public static void clearContext() {
        MDC.clear();
    }

    /**
     * Logs the start of an operation with timing.
     * @param operation the operation name
     * @return the start timestamp in milliseconds
     */
    public static long logOperationStart(String operation) {
        String op = resolveOperationName(operation);
        long startTime = System.currentTimeMillis();
        log.info("event=operation_start operation={}", op);
        return startTime;
    }

    /**
     * Logs the completion of an operation with duration.
     * @param operation the operation name
     * @param startTime the start timestamp in milliseconds
     */
    public static void logOperationComplete(String operation, long startTime) {
        String op = resolveOperationName(operation);
        long duration = System.currentTimeMillis() - startTime;
        log.info("event=operation_complete operation={} duration_ms={}", op, duration);
    }

    /**
     * Logs an operation failure with duration and error details.
     * @param operation the operation name
     * @param startTime the start timestamp in milliseconds
     * @param error the error that occurred
     */
    public static void logOperationFailure(String operation, long startTime, Exception error) {
        String op = resolveOperationName(operation);
        long duration = System.currentTimeMillis() - startTime;
        String errorMessage = Objects.nonNull(error) ? error.getMessage() : UNKNOWN;
        log.error("event=operation_failure operation={} duration_ms={} error={}", op, duration, errorMessage, error);
    }

    /**
     * Logs audit information for workflow events.
     * @param eventType the type of event
     * @param correlationId the correlation ID of the current operation
     * @param details additional details about the event
     */
    public static void logAuditEvent(String eventType, String correlationId, String details) {
        String type = Objects.nonNull(eventType) ? eventType : UNKNOWN;
        String id = Objects.nonNull(correlationId) ? correlationId : UNKNOWN;
        String det = Objects.nonNull(details) ? details : UNKNOWN;
        log.info("event=audit event_type={} correlation_id={} details={}", type, id, det);
    }

    /**
     * Logs performance metrics.
     * @param metricName the metric name
     * @param value the metric value (supports decimals for durations)
     * @param unit the unit of measurement
     */
    public static void logPerformanceMetric(String metricName, double value, String unit) {
        String name = Objects.nonNull(metricName) ? metricName : UNKNOWN;
        String u = Objects.nonNull(unit) ? unit : UNKNOWN;
        log.info("event=metric metric_name={} value={} unit={}", name, value, u);
    }

    /**
     * Overloaded method for long values for backwards compatibility.
     * @param metricName the metric name
     * @param value the metric value
     * @param unit the unit of measurement
     */
    public static void logPerformanceMetric(String metricName, long value, String unit) {
        logPerformanceMetric(metricName, (double) value, unit);
    }

    /**
     * Gets the current correlation ID from MDC.
     * @return the correlation ID or null if not set
     */
    public static String getCorrelationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }

    /**
     * Resolves operation name, falling back to "unknown" if null or blank.
     */
    private static String resolveOperationName(String operation) {
        return (Objects.nonNull(operation) && !operation.isBlank()) ? operation : UNKNOWN;
    }
}