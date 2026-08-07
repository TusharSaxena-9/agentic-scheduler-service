package com.yesmadam.schedulerservice.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.Loggable;

/**
 * AOP aspect for automatic method logging. Intercepts methods annotated with {@link Loggable} and
 * logs operation start, completion, and failure with timing information.
 *
 * <p>Runs as the innermost aspect (@Order(2)) so it is closest to the actual method execution,
 * ensuring accurate timing and failure logging before ExceptionHandlingAspect rethrows.
 */
@Aspect
@Component
@Order(2)
public class LoggingAspect {

  @Around("@annotation(loggable)")
  public Object logExecution(ProceedingJoinPoint jp, Loggable loggable) throws Throwable {
    String operation =
        loggable.operation().isEmpty() ? jp.getSignature().getName() : loggable.operation();

    LoggingUtils.setCorrelationId(null);
    long startTime = LoggingUtils.logOperationStart(operation);

    try {
      Object result = jp.proceed();
      LoggingUtils.logOperationComplete(operation, startTime);
      return result;

    } catch (Throwable ex) {
      LoggingUtils.logOperationFailure(
          operation,
          startTime,
          ex instanceof Exception ? (Exception) ex : new RuntimeException(ex));
      throw ex;

    } finally {
      LoggingUtils.clearContext();
    }
  }
}
