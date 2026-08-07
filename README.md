# Scheduler Service

Temporal-based scheduling service responsible for executing recurring and background jobs with retry support.

---

## Tech Stack

- **Java 17**
- **Spring Boot 2.7.18**
- **Temporal SDK 1.22.0**
- **Spring Data JPA + MySQL**
- **Spring AOP**
- **Lombok**

---

## Package Structure

```
com.yesmadam.schedulerservice
│
├── annotation
│   ├── Loggable.java               # Marks methods for automatic logging via AOP
│   └── HandleExceptions.java       # Marks methods for automatic exception handling via AOP
│
├── config
│   ├── TemporalConfig.java         # WorkflowServiceStubs, WorkflowClient, ScheduleClient beans
│   └── TemporalWorkerConfig.java   # WorkerFactory, Worker beans, ApplicationRunner to start workers
│
├── exception
│   ├── dto
│   │   ├── ErrorInfo.java          # Internal error DTO for logging and monitoring
│   │   └── ErrorResponse.java      # API error response DTO
│   └── handler
│       ├── GlobalExceptionHandler.java     # Centralized exception handling and logging
│       └── ExceptionHandlingAspect.java    # AOP aspect for @HandleExceptions
│
├── logging
│   ├── LoggingUtils.java           # MDC-based structured logging utilities
│   └── LoggingAspect.java          # AOP aspect for @Loggable
│
└── {feature}                       # e.g. booking, payment, reminder
    ├── model                       # JPA entity
    ├── repository                  # Spring Data JPA repository
    ├── activity                    # Temporal activity interface and implementation
    ├── workflow                    # Temporal workflow interface and implementation
    └── schedule                    # @PostConstruct schedule registration
```

---

## AOP Annotations

### `@Loggable`
Automatically logs operation start, completion, failure, and timing.

```java
@Loggable(operation = "fetch-booking-counts")
public List<BookingCount> fetchBookingCounts() { ... }
```

### `@HandleExceptions`
Automatically delegates exceptions to `GlobalExceptionHandler` and rethrows for Temporal retry.

```java
@HandleExceptions
public List<BookingCount> fetchBookingCounts() { ... }
```

### Using both together (recommended for activity methods)
```java
@Loggable(operation = "fetch-booking-counts")
@HandleExceptions
public List<BookingCount> fetchBookingCounts() { ... }
```

**Aspect execution order:**
1. `ExceptionHandlingAspect` (@Order 1 — outermost)
2. `LoggingAspect` (@Order 2 — innermost)
3. Actual method

---

## Logging

Structured `key=value` log format for easy querying in log aggregators (ELK, Datadog, CloudWatch).

```
event=operation_start operation=fetch-booking-counts
event=operation_complete operation=fetch-booking-counts duration_ms=42
event=operation_failure operation=fetch-booking-counts duration_ms=12 error=...
event=audit event_type=WORKFLOW_STARTED correlation_id=abc-123 details=...
event=metric metric_name=booking-counts-processed value=10 unit=records
```

MDC correlation ID is set automatically by `LoggingAspect` and cleared in `finally`.

---

## Adding a New Cron Job

### 1. Create a new feature package

```
src/main/java/com/yesmadam/schedulerservice/{feature}/
    ├── model/          YourEntity.java
    ├── repository/     YourRepo.java
    ├── activity/       YourActivities.java (interface)
                        YourActivitiesImpl.java
    ├── workflow/       YourWorkflow.java (interface)
                        YourWorkflowImpl.java
    └── schedule/       YourSchedule.java
```

### 2. Implement your activity methods

```java
@Loggable(operation = "your-operation-name")
@HandleExceptions
public void yourActivityMethod() {
    // business logic only — no try-catch, no logging boilerplate
}
```

### 3. Implement your workflow

```java
@Loggable(operation = "your-workflow-name")
public void run() {
    // call activities, log audit events
}
```

### 4. Register the worker in `TemporalWorkerConfig.java`

```java
@Bean
public Worker yourWorker(WorkerFactory factory, YourActivities yourActivities) {
    Worker worker = factory.newWorker(yourTaskQueue);
    worker.registerWorkflowImplementationTypes(YourWorkflowImpl.class);
    worker.registerActivitiesImplementations(yourActivities);
    return worker;
}
```

### 5. Add properties to `application.properties`

```properties
temporal.taskqueue.your-feature=your-feature-task-queue
temporal.schedule.your-feature=your-feature-schedule
temporal.cron.your-feature=0 8 * * *
```

### 6. Done — nothing else changes

---

## Code Style

This project uses **Google Java Format** enforced via Spotless and Checkstyle.

Before committing, always run:
```bash
mvn spotless:apply
```

To verify the full build including style checks:
```bash
mvn verify
```