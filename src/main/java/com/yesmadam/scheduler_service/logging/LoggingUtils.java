package com.yesmadam.scheduler_service.logging;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Utility class for structured logging with correlation IDs.
 * Provides methods to manage MDC context and structured logging.
 */
@Slf4j
public class LoggingUtils {

    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String WORKFLOW_ID_KEY = "workflowId";
    private static final String ACTIVITY_KEY = "activity";

    /**
     * Sets a correlation ID in the MDC context.
     * @param correlationId the correlation ID to set, generates one if null
     * @return the correlation ID that was set
     */
    public static String setCorrelationId(String correlationId) {
        String id = correlationId != null ? correlationId : UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID_KEY, id);
        return id;
    }

    /**
     * Sets workflow ID in the MDC context.
     * @param workflowId the workflow ID to set
     */
    public static void setWorkflowId(String workflowId) {
        MDC.put(WORKFLOW_ID_KEY, workflowId);
    }

    /**
     * Sets activity name in the MDC context.
     * @param activityName the activity name to set
     */
    public static void setActivity(String activityName) {
        MDC.put(ACTIVITY_KEY, activityName);
    }

    /**
     * Clears the MDC context.
     */
    public static void clearContext() {
        MDC.clear();
    }

    /**
     * Logs the start of an operation with timing.
     * @param operation the operation name
     * @return the start timestamp
     */
    public static long logOperationStart(String operation) {
        long startTime = System.currentTimeMillis();
        log.info("Starting operation: {}", operation);
        return startTime;
    }

    /**
     * Logs the completion of an operation with duration.
     * @param operation the operation name
     * @param startTime the start timestamp
     */
    public static void logOperationComplete(String operation, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        log.info("Completed operation: {} in {}ms", operation, duration);
    }

    /**
     * Logs an operation failure with duration and error details.
     * @param operation the operation name
     * @param startTime the start timestamp
     * @param error the error that occurred
     */
    public static void logOperationFailure(String operation, long startTime, Exception error) {
        long duration = System.currentTimeMillis() - startTime;
        log.error("Failed operation: {} in {}ms - Error: {}", operation, duration, error.getMessage(), error);
    }

    /**
     * Logs audit information for workflow events.
     * @param eventType the type of event
     * @param workflowId the workflow ID
     * @param details additional details about the event
     */
    public static void logAuditEvent(String eventType, String workflowId, String details) {
        log.info("AUDIT - Event: {}, Workflow: {}, Details: {}", eventType, workflowId, details);
    }

    /**
     * Logs performance metrics.
     * @param metricName the metric name
     * @param value the metric value
     * @param unit the unit of measurement
     */
    public static void logPerformanceMetric(String metricName, long value, String unit) {
        log.info("METRIC - {}: {} {}", metricName, value, unit);
    }

    /**
     * Gets the current correlation ID from MDC.
     * @return the correlation ID or null if not set
     */
    public static String getCorrelationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }
}
