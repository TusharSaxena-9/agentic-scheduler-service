package com.yesmadam.scheduler_service.exception.dto;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * Error information DTO for internal error handling and logging. Used by the global exception
 * handler for structured error reporting.
 */
@Data
@Builder
public class ErrorInfo {

  /** Error category or type. */
  private String errorType;

  /** Human-readable error message. */
  private String message;

  /** Timestamp when the error occurred. Automatically set to now. */
  @Builder.Default private LocalDateTime timestamp = LocalDateTime.now();

  /** Unique identifier for tracking the error (correlation ID). */
  private String traceId;

  /** Component or module where the error occurred. */
  private String component;

  /** Additional error details (optional). */
  private String details;
}
