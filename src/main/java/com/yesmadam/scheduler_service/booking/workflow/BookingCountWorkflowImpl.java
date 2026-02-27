package com.yesmadam.scheduler_service.booking.workflow;

import com.yesmadam.scheduler_service.booking.activity.BookingActivities;
import com.yesmadam.scheduler_service.booking.model.BookingCount;
import com.yesmadam.scheduler_service.logging.LoggingUtils;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

@Slf4j
public class BookingCountWorkflowImpl implements BookingCountWorkflow {

    private final BookingActivities activities = Workflow.newActivityStub(
        BookingActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .build()
    );
    
    // Note: GlobalExceptionHandler is not injected here as workflows should be stateless
    // Exception handling is done at the activity level where appropriate logging occurs

    /**
     * Executes the booking count workflow.
     * Fetches booking counts from activities and saves them if they exist.
     */
    @Override
    public void run() {
        String workflowId = Workflow.getInfo().getWorkflowId();
        String correlationId = LoggingUtils.setCorrelationId(null);
        LoggingUtils.setWorkflowId(workflowId);
        
        long workflowStartTime = LoggingUtils.logOperationStart("booking-count-workflow");
        
        try {
            LoggingUtils.logAuditEvent("WORKFLOW_STARTED", workflowId, "Booking count workflow initiated");
            
            // Fetch booking counts
            long fetchStartTime = LoggingUtils.logOperationStart("fetch-booking-counts");
            LoggingUtils.setActivity("fetchBookingCounts");
            List<BookingCount> counts = activities.fetchBookingCounts();
            LoggingUtils.logOperationComplete("fetch-booking-counts", fetchStartTime);
            
            if (counts != null && !counts.isEmpty()) {
                // Save booking counts
                long saveStartTime = LoggingUtils.logOperationStart("save-booking-counts");
                LoggingUtils.setActivity("saveBookingCounts");
                activities.saveBookingCounts(counts);
                LoggingUtils.logOperationComplete("save-booking-counts", saveStartTime);
                
                LoggingUtils.logAuditEvent("WORKFLOW_COMPLETED", workflowId, 
                    String.format("Processed %d booking counts", counts.size()));
                LoggingUtils.logPerformanceMetric("booking-counts-processed", counts.size(), "records");
            } else {
                LoggingUtils.logAuditEvent("WORKFLOW_COMPLETED", workflowId, "No booking counts to process");
            }
            
            LoggingUtils.logOperationComplete("booking-count-workflow", workflowStartTime);
            
        } catch (Exception e) {
            LoggingUtils.logOperationFailure("booking-count-workflow", workflowStartTime, e);
            LoggingUtils.logAuditEvent("WORKFLOW_FAILED", workflowId, 
                String.format("Workflow failed: %s", e.getMessage()));
            // Re-throw for Temporal retry mechanism
            throw e;
            
        } finally {
            LoggingUtils.clearContext();
        }
    }
}
