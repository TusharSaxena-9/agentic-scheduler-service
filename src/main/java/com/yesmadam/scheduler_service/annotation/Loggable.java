package com.yesmadam.scheduler_service.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic logging. Logs operation start, completion, and failure with timing
 * information. Works in conjunction with {@link HandleExceptions} for full observability.
 *
 * <p>Usage: {@code @Loggable(operation = "fetch-booking-counts")}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Loggable {

  /** Optional custom operation name for logging. Defaults to the method name if not specified. */
  String operation() default "";
}
