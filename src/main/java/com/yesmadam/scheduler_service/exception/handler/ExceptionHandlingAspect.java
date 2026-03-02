package com.yesmadam.scheduler_service.exception.handler;

import com.yesmadam.scheduler_service.annotation.HandleExceptions;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * AOP aspect for automatic exception handling.
 * Intercepts methods annotated with {@link HandleExceptions} and delegates
 * to {@link GlobalExceptionHandler} based on exception type.
 *
 * Runs as the outermost aspect (@Order(1)) so that LoggingAspect can log
 * the failure before the exception is handled and rethrown.
 *
 * All exceptions are rethrown after handling so Temporal can trigger retries.
 */
@Aspect
@Component
@Order(1)
public class ExceptionHandlingAspect {

    private final GlobalExceptionHandler exceptionHandler;

    public ExceptionHandlingAspect(GlobalExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    @Around("@annotation(handleExceptions)")
    public Object handleExceptions(ProceedingJoinPoint jp, HandleExceptions handleExceptions) throws Throwable {
        try {
            return jp.proceed();

        } catch (DataAccessException ex) {
            throw exceptionHandler.handleDataAccessException(ex);

        } catch (RuntimeException ex) {
            exceptionHandler.handleRuntimeException(ex);
            throw ex;

        } catch (Exception ex) {
            exceptionHandler.handleGenericException(ex);
            throw new RuntimeException("Unexpected error in " + jp.getSignature().getName(), ex);
        }
    }
}