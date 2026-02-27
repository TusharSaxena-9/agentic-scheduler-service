package com.yesmadam.scheduler_service.config;

import com.yesmadam.scheduler_service.booking.activity.BookingActivities;
import com.yesmadam.scheduler_service.booking.workflow.BookingCountWorkflowImpl;
import com.yesmadam.scheduler_service.exception.handler.GlobalExceptionHandler;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class TemporalWorkerConfig {

    @Value("${temporal.taskqueue.booking}")
    private String bookingTaskQueue;
    
    private final GlobalExceptionHandler exceptionHandler;
    
    public TemporalWorkerConfig(@Autowired GlobalExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Creates and configures the WorkerFactory bean.
     * @param workflowClient the WorkflowClient dependency
     * @return a configured WorkerFactory instance
     */
    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        long startTime = LoggingUtils.logOperationStart("create-worker-factory");
        
        try {
            WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
            LoggingUtils.logOperationComplete("create-worker-factory", startTime);
            log.info("Temporal WorkerFactory created successfully");
            
            return factory;
            
        } catch (RuntimeException ex) {
            exceptionHandler.handleRuntimeException(ex);
            throw ex;
        } catch (Exception ex) {
            exceptionHandler.handleGenericException(ex);
            throw new RuntimeException("Failed to create WorkerFactory", ex);
        }
    }

    /**
     * Creates and configures the booking worker.
     * Registers workflow implementations and activity implementations.
     * @param factory the WorkerFactory dependency
     * @param bookingActivities the BookingActivities dependency
     * @return a configured Worker instance
     */
    @Bean
    public Worker bookingWorker(WorkerFactory factory, BookingActivities bookingActivities) {
        long startTime = LoggingUtils.logOperationStart("create-booking-worker");
        
        try {
            Worker worker = factory.newWorker(bookingTaskQueue);
            worker.registerWorkflowImplementationTypes(BookingCountWorkflowImpl.class);
            worker.registerActivitiesImplementations(bookingActivities);
            
            LoggingUtils.logOperationComplete("create-booking-worker", startTime);
            log.info("Booking worker created successfully for task queue: {}", bookingTaskQueue);
            
            return worker;
            
        } catch (RuntimeException ex) {
            exceptionHandler.handleRuntimeException(ex);
            throw ex;
        } catch (Exception ex) {
            exceptionHandler.handleGenericException(ex);
            throw new RuntimeException("Failed to create booking worker for task queue: " + bookingTaskQueue, ex);
        }
    }
    // Starts all workers after all beans are registered
    /**
     * Creates an ApplicationRunner to start all workers after bean initialization.
     * @return an ApplicationRunner that starts the WorkerFactory
     */
    @Bean
    public ApplicationRunner startWorkers(WorkerFactory factory) {
        return args -> {
            long startTime = LoggingUtils.logOperationStart("start-temporal-workers");
            
            try {
                factory.start();
                LoggingUtils.logOperationComplete("start-temporal-workers", startTime);
                log.info("All Temporal workers started successfully");
                LoggingUtils.logAuditEvent("WORKERS_STARTED", "TEMPORAL", "All Temporal workers have been started");
                
            } catch (RuntimeException ex) {
                exceptionHandler.handleRuntimeException(ex);
                throw ex;
            } catch (Exception ex) {
                exceptionHandler.handleGenericException(ex);
                LoggingUtils.logAuditEvent("WORKERS_START_FAILED", "TEMPORAL", 
                    String.format("Failed to start workers: %s", ex.getMessage()));
                throw new RuntimeException("Failed to start Temporal workers", ex);
            }
        };
    }
}
