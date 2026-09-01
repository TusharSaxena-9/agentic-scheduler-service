# Scheduler Service

Temporal-based scheduling service responsible for executing recurring and background jobs with retry support.

The full contract for adding or migrating a job — determinism rules, retry/timeout sizing, task
queue tiering, schedule policy, reader/writer routing, idempotency — lives in
[`CLAUDE.md`](CLAUDE.md), not here. This file is an orientation map of the codebase; treat
`CLAUDE.md` as the living source of truth whenever the two disagree.

---

## Tech Stack

- **Java 17**
- **Spring Boot 2.7.18**
- **Temporal SDK 1.31.0** (via `temporal-spring-boot-starter`)
- **Spring Data JPA + MySQL** (separate reader/writer connection pools, see `config/DataSourceConfig.java`)
- **Spring AOP** (`@Loggable`, `@HandleExceptions`)
- **Lombok**

---

## Package Structure

Workers are **not** wired up by hand anywhere in this codebase — there is no `TemporalConfig.java`
or `TemporalWorkerConfig.java`. The Temporal Spring Boot starter auto-discovers everything under
`com.yesmadam.schedulerservice` (`spring.temporal.workers-auto-discovery.packages` in
`application.properties`) by scanning for `@WorkflowImpl` / `@ActivityImpl` annotations, and attaches
each one to whichever task queue its annotation names.

```
com.yesmadam.schedulerservice
│
├── annotation
│   ├── Loggable.java               # Marks methods for automatic logging via AOP
│   └── HandleExceptions.java       # Marks methods for automatic exception handling via AOP
│
├── config
│   ├── DataSourceConfig.java           # Writer + reader HikariCP pools, routing DataSource bean
│   ├── ReadWriteRoutingDataSource.java # Picks reader/writer per transaction's readOnly flag
│   ├── DataSourceType.java             # READER / WRITER lookup keys
│   ├── ScheduleReconciler.java         # Reads schedules.yml, creates missing schedules on startup
│   ├── JobScheduleProperties.java      # Binds the `scheduler.jobs[]` block of schedules.yml
│   ├── JobDefinition.java              # One job entry from schedules.yml
│   ├── TaskQueueTier.java              # LIGHT / STANDARD / HEAVY / NOTIFICATION enum
│   ├── TemporalWorkerAuditLogger.java  # Audit-logs once the starter's workers have started
│   └── RestTemplateConfig.java         # RestTemplate bean for outbound HTTP (WhatsApp, etc.)
│
├── common
│   ├── temporal
│   │   ├── CronScheduleRequest.java      # Resolved schedule definition passed to the registrar
│   │   └── TemporalScheduleRegistrar.java # Applies service-wide schedule defaults, calls the SDK
│   ├── mail/MailService.java
│   ├── whatsapp/                         # Outbound WhatsApp alert client + DTOs
│   └── constants/DBConstants.java        # MySQL catalog name constants (ysmdm_users, ysmdm_admin)
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
└── jobs
    └── {feature}                   # e.g. passbookmismatch, sebaltagging, order/ordercancellationcharge
        ├── model                   # JPA entity — or reuse one from ym-common-entity if it exists there
        ├── repository              # Spring Data JPA repository
        ├── service                 # Business logic, no io.temporal imports (unit-testable)
        ├── activity                # Temporal activity interface + implementation (thin adapter)
        └── workflow                # Temporal workflow interface + implementation (orchestration only)
```

There is no `schedule/` subpackage and no `@PostConstruct` schedule registration anymore — see
"Adding a New Cron Job" below.

---

## Task Queues

Four fixed tiers, not one queue per job — each is a slot pool sized for a different resource
profile:

| Property | Queue name | Profile |
|---|---|---|
| `temporal.taskqueue.light` | `scheduler-light` | sub-second to ~1 min, high frequency |
| `temporal.taskqueue.standard` | `scheduler-standard` | seconds to minutes (the default) |
| `temporal.taskqueue.heavy` | `scheduler-heavy` | multi-minute to multi-hour, chunked, few slots |
| `temporal.taskqueue.notification` | `scheduler-notification` | outbound email/WhatsApp/HTTP |

A job's `@WorkflowImpl(taskQueues = "${temporal.taskqueue.X}")` and its activities'
`@ActivityImpl(taskQueues = "${temporal.taskqueue.X}")` must reference the **same** tier as the
`tier:` field for that job in `schedules.yml` — nothing cross-checks this at runtime (a mismatch
produces a schedule that dispatches to a queue nobody polls, silently, with no error), so
`WorkflowTierAgreementTest` enforces it at build time instead.

---

## AOP Annotations

### `@Loggable`
Automatically logs operation start, completion, failure, and timing.

```java
@Loggable(operation = "fetch-booking-counts")
public List<BookingCount> fetchBookingCounts() { ... }
```

### `@HandleExceptions`
Automatically delegates exceptions to `GlobalExceptionHandler` (classifies by type, logs an audit
event and a metric line) and rethrows so Temporal can trigger retries.

```java
@HandleExceptions
public List<BookingCount> fetchBookingCounts() { ... }
```

### Using both together (convention for every activity method)
```java
@Loggable(operation = "fetch-booking-counts")
@HandleExceptions
public List<BookingCount> fetchBookingCounts() { ... }
```

**Aspect execution order:**
1. `ExceptionHandlingAspect` (@Order 1 — outermost)
2. `LoggingAspect` (@Order 2 — innermost)
3. Actual method

**Known behavior, not a bug:** on failure, both aspects log the same exception with its full stack
trace independently (`LoggingAspect`'s `event=operation_failure` and
`GlobalExceptionHandler`'s `event=error`) — one failure produces two stack traces in the logs.
`@Loggable`'s `trace_id`/correlation ID is a locally-generated random UUID for `grep`-ing related
log lines, not a distributed trace ID — this service has no tracing backend (no OpenTelemetry/Tempo/
Jaeger) wired up, so it can't be used to view a request-style trace in Grafana. It's also
regenerated fresh on every `@Loggable` call rather than inherited from a caller, so it doesn't even
correlate one workflow run's own log lines together.

**Only use these on activity implementations, never on workflow methods.** Workflow code is
replayed by Temporal, and both aspects have side effects (logging, MDC writes) that would re-fire on
every replay — see rule 1 and rule 2 in `CLAUDE.md`.

---

## Logging

Structured `key=value` log format for easy querying in Grafana Loki.

```
event=operation_start operation=fetch-booking-counts
event=operation_complete operation=fetch-booking-counts duration_ms=42
event=operation_failure operation=fetch-booking-counts duration_ms=12 error=...
event=audit event_type=WORKFLOW_STARTED correlation_id=abc-123 details=...
event=metric metric_name=booking-counts-processed value=10 unit=records
```

MDC correlation ID is set automatically by `LoggingAspect` and cleared in `finally`. Note this is a
log-correlation field only (see the caveat above), not a Prometheus/Micrometer metric or a
distributed trace — `event=metric` lines are plain log text, not values pushed to a metrics
registry.

---

## Adding a New Cron Job

The full checklist (determinism, retries, timeouts, idempotency, reader/writer routing, task queue
tiering) is in `CLAUDE.md` — "Migrating a job from cron / messagingapis / tracking" and rules 1–15.
Summary of the moving parts:

### 1. Create a new package under `jobs/`

```
src/main/java/com/yesmadam/schedulerservice/jobs/{feature}/
    ├── model/          YourEntity.java        (or reuse one already in ym-common-entity)
    ├── repository/     YourRepository.java
    ├── service/        YourService.java       # business logic, no io.temporal imports
    ├── activity/       YourActivities.java (interface)
    │                   YourActivitiesImpl.java
    └── workflow/       YourWorkflow.java (interface)
                        YourWorkflowImpl.java
```

### 2. Implement your activity methods — thin adapters over the service

```java
@Loggable(operation = "your-operation-name")
@HandleExceptions
public YourResult yourActivityMethod() {
    return yourService.doTheWork();
}
```

### 3. Implement your workflow — orchestration only, no `@Loggable`

```java
@WorkflowImpl(taskQueues = "${temporal.taskqueue.standard}")  // pick an existing tier
public class YourWorkflowImpl implements YourWorkflow {
    private static final Logger log = Workflow.getLogger(YourWorkflowImpl.class);
    // Workflow.newActivityStub(...) with explicit timeouts + retry options, then call it.
}
```

### 4. Nothing to register by hand

No `TemporalWorkerConfig.java`, no `@Bean` `Worker`. `@WorkflowImpl`/`@ActivityImpl` are enough —
auto-discovery picks them up and attaches them to the queue their annotation names, as long as that
queue is one of the four tiers already configured in `application.properties`
(`spring.temporal.workers[0..3]`). Adding a fifth tier is an architectural decision, not a per-job
one (rule 6).

### 5. Declare the schedule in `schedules.yml`

```yaml
- name: your-feature
  workflow: com.yesmadam.schedulerservice.jobs.yourfeature.workflow.YourWorkflow
  tier: standard          # must match step 3's @WorkflowImpl queue
  cron: "0 8 * * *"       # Temporal 5-field cron, not Spring's 6-field/`?` dialect
  enabled: false           # applies at creation only — see the file's own header comment
  owner: platform
  description: What this job does
```

`ScheduleReconciler` is **create-only**: it creates the schedule on next startup if missing, but
editing an existing job's `schedules.yml` entry does nothing until you delete that schedule in the
Temporal UI first. There is no `temporal.schedule.your-feature` / `temporal.cron.your-feature`
property to add anywhere — cron, tier, and policy all live in this one YAML entry.

### 6. Write service-layer tests, not workflow tests

Rule 12: cover the service layer's branches with plain JUnit (no Temporal test environment needed,
since the service has no `io.temporal` imports), plus one test proving the activity returns a
bounded payload rather than an unbounded list (rule 9).

---

## Code Style

This project uses **Google Java Format** (via `google-java-format` 1.17.0) enforced by Spotless and
Checkstyle (Google's `google_checks.xml`), both bound to the `verify` phase.

Before committing, always run:
```bash
./mvnw spotless:apply
```

To verify the full build including style checks:
```bash
./mvnw verify
```
