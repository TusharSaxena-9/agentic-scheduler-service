package com.yesmadam.scheduler_service.exception.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Standard error response DTO for consistent API error responses.
 * Provides a structured format for all error responses across the application.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    
    /**
     * HTTP status code of the error.
     */
    private int status;
    
    /**
     * Error category or type.
     */
    private String error;
    
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
     * Additional error details (optional).
     */
    private String details;
    
    /**
     * Creates an error response with basic information.
     * @param status HTTP status code
     * @param error error category
     * @param message error message
     */
    public ErrorResponse(int status, String error, String message) {
        this(status, error, message, LocalDateTime.now(), null, null);
    }
    
    /**
     * Creates an error response with trace ID.
     * @param status HTTP status code
     * @param error error category
     * @param message error message
     * @param traceId correlation/trace ID
     */
    public ErrorResponse(int status, String error, String message, String traceId) {
        this(status, error, message, LocalDateTime.now(), traceId, null);
    }
}
