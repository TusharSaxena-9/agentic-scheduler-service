package com.yesmadam.scheduler_service.booking.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface BookingCountWorkflow {
    @WorkflowMethod
    void run();
}
