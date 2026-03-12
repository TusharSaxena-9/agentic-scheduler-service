package com.yesmadam.scheduler_service.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic exception handling via {@link
 * com.yesmadam.scheduler_service.exception.handler.GlobalExceptionHandler}. Catches, logs, and
 * rethrows exceptions so Temporal can trigger retries where appropriate.
 *
 * <p>Exception handling order: 1. DataAccessException → handleDataAccessException (retryable) 2.
 * RuntimeException → handleRuntimeException (retryable) 3. Exception → handleGenericException,
 * wrapped in RuntimeException (retryable)
 *
 * <p>Usage: {@code @HandleExceptions}
 *
 * <p>Typically used alongside {@link Loggable}: {@code @Loggable(operation =
 * "fetch-booking-counts")} {@code @HandleExceptions}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HandleExceptions {}
