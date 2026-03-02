package com.yesmadam.scheduler_service.booking.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.yesmadam.scheduler_service.annotation.Loggable;
import com.yesmadam.scheduler_service.booking.activity.BookingActivities;
import com.yesmadam.scheduler_service.booking.model.BookingCount;
import com.yesmadam.scheduler_service.logging.LoggingUtils;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BookingCountWorkflowImpl implements BookingCountWorkflow {

  private final BookingActivities activities =
      Workflow.newActivityStub(
          BookingActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(5)).build());

  // Note: GlobalExceptionHandler is not injected here as workflows should be stateless.
  // Exception handling and logging is done at the activity level.
  // MDC context is not used here as Temporal workflows can be replayed
  // and MDC context does not persist across replays.

  /**
   * Executes the booking count workflow. Fetches booking counts from activities and saves them if
   * they exist.
   */
  @Override
  @Loggable(operation = "booking-count-workflow")
  public void run() {
    String workflowId = Workflow.getInfo().getWorkflowId();

    LoggingUtils.logAuditEvent("WORKFLOW_STARTED", workflowId, "Booking count workflow initiated");

    List<BookingCount> counts = activities.fetchBookingCounts();

    if (Objects.isNull(counts) || counts.isEmpty()) {
      LoggingUtils.logAuditEvent("WORKFLOW_COMPLETED", workflowId, "reason=no_counts_to_process");
      return;
    }

    activities.saveBookingCounts(counts);

    LoggingUtils.logAuditEvent(
        "WORKFLOW_COMPLETED", workflowId, String.format("records_processed=%d", counts.size()));
    LoggingUtils.logPerformanceMetric("booking-counts-processed", counts.size(), "records");
  }
}
