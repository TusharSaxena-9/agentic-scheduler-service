package com.yesmadam.scheduler_service.exception.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Error information DTO for internal error handling and logging.
 * Used by the global exception handler for structured error reporting.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorInfo {
    
    /**
     * Error category or type.
     */
    private String errorType;
    
    /**
     * Human-readable error message.
     */
    private String message;
    
    /**
     * Timestamp when the error occurred.
     */
    private LocalDateTime timestamp;
    
    /**
     * Unique identifier for tracking the error (correlation ID).
     */
    private String traceId;
    
    /**
     * Component or module where the error occurred.
     */
    private String component;
    
    /**
     * Additional error details (optional).
     */
    private String details;
    
    /**
     * Creates an error info with basic information.
     * @param errorType error category
     * @param message error message
     * @param component component where error occurred
     */
    public ErrorInfo(String errorType, String message, String component) {
        this(errorType, message, LocalDateTime.now(), null, component, null);
    }
    
    /**
     * Creates an error info with trace ID.
     * @param errorType error category
     * @param message error message
     * @param traceId correlation/trace ID
     * @param component component where error occurred
     */
    public ErrorInfo(String errorType, String message, String traceId, String component) {
        this(errorType, message, LocalDateTime.now(), traceId, component, null);
    }

    public ErrorInfo(String errorType, String message, String traceId, String component, String details) {
        this.errorType = errorType;
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.traceId = traceId;
        this.component = component;
        this.details = details;
    }
}
