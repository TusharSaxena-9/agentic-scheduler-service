package com.yesmadam.scheduler_service.booking.workflow;

import com.yesmadam.scheduler_service.booking.activity.BookingActivities;
import com.yesmadam.scheduler_service.booking.model.BookingCount;
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

    @Override
    public void run() {
        List<BookingCount> counts = activities.fetchBookingCounts();
        if (counts != null && !counts.isEmpty()) {
            activities.saveBookingCounts(counts);
        }
    }
}
