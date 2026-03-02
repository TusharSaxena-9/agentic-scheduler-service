package com.yesmadam.scheduler_service.exception.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Standard error response DTO for consistent API error responses.
 * Provides a structured format for all error responses across the application.
 */
@Data
@Builder
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
     * Timestamp when the error occurred. Automatically set to now.
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * Unique identifier for tracking the error (correlation ID).
     */
    private String traceId;

    /**
     * Additional error details (optional).
     */
    private String details;
}