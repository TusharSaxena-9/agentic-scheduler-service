# scheduler-service — working rules

Temporal-based scheduling service. This file is the contract for adding or migrating a job.

We are consolidating ~101 scheduled jobs from `cron`, `messagingapis` and `tracking` into this
service. Most of these rules exist because a specific bug was found here first — the reason is given
so you can judge when it genuinely does not apply, rather than following it blindly.

Background: `docs/temporal-migration/` (full review, job inventory, cron translations).

---

## 1. Workflow code is orchestration only

Workflow code is **replayed**. Temporal re-executes it from event history after a worker restart,
cache eviction or deploy, and it must produce the same sequence of commands every time. Anything that
returns a different value on a second execution does not belong there.

Never in a workflow class:

- **Clock reads.** `LocalDateTime.now()`, `LocalDate.now()`, `Instant.now()`,
  `System.currentTimeMillis()`, `new Date()`, `new Timestamp(...)`.
- **Randomness.** `UUID.randomUUID()`, `new Random()`.
- **I/O.** Database, HTTP, file, message queue.
- **Spring injection.** `@Autowired`, `@Value`, injected services. The starter *does* wire these
  (it instantiates workflows via `beanFactory.createBean`), which makes this a trap rather than a
  compile error — injected singleton state can differ between the original run and a replay.
- **Cross-cutting AOP.** No `@Loggable` / `@HandleExceptions` on a workflow method. The aspect runs
  again on every replay, and any side effect it grows later runs again with it.
- **JPA entities in a signature.** See rule 9.

### Where time belongs

Two legal sources, and they mean different things:

| Need | Use |
|---|---|
| "What time is it, deterministically?" | `Workflow.currentTimeMillis()` — the `WorkflowTaskStarted` event time, stable across replay |
| "What window should this run cover?" | Compute it **inside the activity**, and return it alongside the data |
| A delay | `Workflow.sleep(...)` / `Workflow.newTimer(...)`, never `Thread.sleep` |
| Randomness | `Workflow.newRandom()` / `Workflow.randomUUID()` |

Prefer the activity. A window computed in workflow code slides forward on replay, so the run reports
a different window from the one it actually queried — silently, with a green status.
`PassbookMismatchWorkflowImpl` is the reference implementation.

**Always pass an explicit zone.** `ZoneId.of("Asia/Kolkata")`, never a no-arg `now()`. The pod sets
`TZ`, but that lives in the Kubernetes manifest — one edit away from changing every date-bucketing
job, and absent entirely on a developer machine. Schedules fire in the zone set on
`ScheduleSpec.setTimeZoneName(...)`, which is a separate thing from the JVM zone.

## 2. Logging in workflows

`Workflow.getLogger(YourWorkflowImpl.class)`. Not `@Slf4j`, not `LoggerFactory`.

A plain logger re-emits every line on every replay, so one logical run produces N copies and an
incident timeline reads like repeated events. `Workflow.getLogger` suppresses output during replay
only — the original run's logs are unaffected. If you need replay logs while debugging, turn them on
deliberately with `WorkerFactoryOptions.setEnableLoggingInReplay(true)`; replay itself stays visible
in the UI history and in `temporal_workflow_task_replay_latency`.

## 3. Cleanup runs in a detached cancellation scope

A cancelled workflow cancels its pending activities, including the ones in your `finally`. If a
cleanup step must run on the cancellation path:

```java
} finally {
  Workflow.newDetachedCancellationScope(() -> cleanupQuietly(...)).run();
}
```

Swallow failures in cleanup rather than letting them mask the original error.

## 4. Every activity stub caps its retries

The SDK default is **unlimited attempts**. A permanently failing activity will retry forever, hold
the workflow open, and — because schedules default to `SKIP` overlap — silently suppress every
subsequent run of that job. That failure mode is invisible: no failed workflow, no alert.

```java
ActivityOptions.newBuilder()
    .setStartToCloseTimeout(...)          // bounds ONE attempt
    .setScheduleToCloseTimeout(...)       // bounds ALL attempts — set this too
    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
    .build();
```

**Default `setMaximumAttempts(3)`.** Raise it only with a reason. Classify permanent failures —
validation, bad recipient, provider 4xx — as `ApplicationFailure.newNonRetryableFailure(...)` so
three attempts are not spent on an error that cannot succeed.

For a recurring job, remember the schedule is itself a retry mechanism. A best-effort job that cannot
get a database connection is often better failing fast and letting the next tick handle it than
retrying into a saturated database.

### Retry amplification on long activities

A retry re-runs the **whole attempt**, not the part that failed. A 30-minute activity with
`maximumAttempts(3)` is a 90-minute worst case of database work for one scheduled job — on `heavy`,
holding a slot and a connection the entire time.

The fix is not fewer attempts, it is smaller attempts. Chunk the work so the retry boundary is a
chunk, and make the chunk's retry boundary the same as its database transaction boundary. Then a
failure costs one chunk, not one job. `SebalTaggingWorkflowImpl` and
`RoseOrderEligibleSpPoolWorkflowImpl` are the reference; note that in both, the exposure that remains
is the *unchunked* tail — a single large finalising insert — which is where to look first when one of
them overruns.

### Sizing an activity's options from what the activity does

Every stub states its options in full. There is no shared base to inherit from, and
`WorkflowImplementationOptions.setDefaultActivityOptions(...)` will not give you one: it reaches a
stub written as `Workflow.newActivityStub(X.class)` with no options at all, and a stub that passes
any `ActivityOptions` replaces it outright rather than inheriting the fields left unset. Verified on
SDK 1.31.0 — with a service default of 2 attempts, a no-options stub stopped at 2 while a stub
declaring only `startToCloseTimeout` retried unbounded. So an omitted field is not inherited from
anywhere; it is simply absent, and for `maximumAttempts` absent means infinite.

Read the activity implementation before writing the options, and answer four questions:

1. **How long does one attempt take?** Not the average — the slow case, on the largest tenant, when
   the database is busy. That sets `startToCloseTimeout`.
2. **Can it exceed a minute?** Then it needs `heartbeatTimeout` *and* actual
   `Activity.getExecutionContext().heartbeat(...)` calls (rule 10). A heartbeat timeout without
   heartbeat calls fails the activity every time at that interval.
3. **Is running it twice safe?** Retries and `SKIP` overlap both re-run work. If a repeat would
   double-send an email or double-credit a passbook, the idempotency has to live in the activity —
   options cannot give it to you.
4. **Which failures cannot succeed on a retry?** Validation, a bad recipient, a provider 4xx, a
   constraint violation. Those go to `ApplicationFailure.newNonRetryableFailure(...)` or
   `RetryOptions.setDoNotRetry(...)`, otherwise three attempts and their backoff are spent on an
   error that was permanent from the first millisecond.

### The fields, and how to decide each

| Field | Bounds | How to pick it |
|---|---|---|
| `setStartToCloseTimeout` | **one attempt** | ~2× the slow-case duration. Too tight kills work that would have finished; too loose lets a hung connection hold a slot until the run timeout. |
| `setScheduleToCloseTimeout` | **all attempts, plus queue wait** | Must be ≥ `attempts × startToClose` + backoff, or it silently truncates the last retry. Set it as well as `startToClose`, never instead of it. |
| `setHeartbeatTimeout` | silence between heartbeats | Roughly the interval between natural progress points — one chunk, one page — with margin. Required above ~1 minute. |
| `setRetryOptions` → `setMaximumAttempts` | retry count | **3** unless there is a reason. The SDK default is unlimited, and unlimited under `SKIP` overlap suppresses every later run of the job with no failed workflow and no alert. |
| `setRetryOptions` → `setDoNotRetry` | which errors skip retry | Exception types that cannot succeed on a second attempt. |
| `setTaskQueue` | where it runs | Only to route one activity off its workflow's tier — a send to `notification`, say (rule 6). |
| `setScheduleToStartTimeout` | queue wait alone | Usually leave unset. On a busy tier it fails work that was merely waiting for a slot, which looks like a bug in the job rather than a capacity problem. |

### Per-activity options on a single stub

**Start with one set of options for the stub.** That is the right answer for most jobs and the
easiest to read. Reach for this only when one method on the interface genuinely has a different
profile from the rest — a 30-minute chunk sitting next to a 2-second flag update — because splitting
timeouts across two places costs clarity, and paying that for methods that behave the same is a loss.

When you do need it, one stub still covers the whole interface: the three-argument overload takes a
default plus a map keyed by **activity type name** — the method name capitalised, unless
`@ActivityMethod(name = ...)` says otherwise:

```java
Workflow.newActivityStub(
    SebalTaggingActivities.class,
    ActivityOptions.newBuilder()                       // applies to every method
        .setStartToCloseTimeout(Duration.ofMinutes(2))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
        .build(),
    Map.of(
        "ProcessChunk",                                // only this one is long
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setHeartbeatTimeout(Duration.ofMinutes(3))
            .build()));
```

**Map entries merge with the default** — an entry sets only what differs and inherits the rest.
Verified on SDK 1.31.0: with a default of 3 attempts and a 5s timeout, an entry setting only
`maximumAttempts(1)` produced exactly 1 attempt *and* kept the 5s timeout it never declared.

Note this is the opposite of `WorkflowImplementationOptions.setDefaultActivityOptions(...)`, which
does not merge into a stub that declares its own options at all. Same word, two behaviours: within
one stub, merging; between the worker-level default and a stub, replacement. Do not reason from one
to the other.

There is also `@MethodRetry` on the activity interface method (`maximumAttempts`, `doNotRetry`, the
backoff fields). Prefer the map: retry policy stated on the interface applies to every caller and is
invisible from the workflow that owns the timeout, which makes the two halves of one decision drift
apart.

### Defaults when you genuinely cannot measure

For a migrated job with no production numbers yet. Use the row matching the activity's shape, leave
a comment saying the value is a placeholder, and replace it once one real run has been observed.

| Activity shape | startToClose | heartbeat | scheduleToClose | attempts |
|---|---|---|---|---|
| Indexed lookup, small write, flag update | 30s | — | 3m | 3 |
| Query or batch write over a bounded set (< ~10k rows) | 5m | — | 20m | 3 |
| One chunk of a chunked job | 30m | 3m | 2h | 3 |
| Large finalising insert or aggregate | 10m | 2m | 40m | 2 |
| Outbound send — mail, WhatsApp, HTTP | 1m | — | 5m | 3, 4xx non-retryable |

Two things these numbers are not: they are not a licence to skip measuring, and they are not safe for
an activity that is not idempotent — for that one, work out the idempotency first and the timeouts
second.

```java
private final SebalTaggingActivities activities =
    Workflow.newActivityStub(
        SebalTaggingActivities.class,
        ActivityOptions.newBuilder()
            // One chunk is ~25s measured; 30m absorbs a slow tenant without holding a heavy slot.
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setHeartbeatTimeout(Duration.ofMinutes(3))     // heartbeats once per page
            .setScheduleToCloseTimeout(Duration.ofHours(2)) // 3 x 30m + backoff, matches run timeout
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());
```

## 5. Schedule policy is a deliberate decision, not a default

`TemporalScheduleRegistrar` applies a default for each of these from `temporal.schedule.defaults.*`,
and a job overrides one by setting the matching field in `schedules.yml` (see rule 7). Inheriting the
default is fine — inheriting it *without having thought about it* is not. Decide each against what
the job is actually for:

| Setting | Owner | Decide by asking |
|---|---|---|
| **Overlap** | `SchedulePolicy.setOverlap(...)` | If a run overruns, should the next one be dropped (`SKIP`), queued (`BUFFER_ONE`), or run anyway (`ALLOW_ALL`)? Default is `SKIP` — for a job whose data must not be missed, that silently loses work. |
| **Catchup window** | `SchedulePolicy.setCatchupWindow(...)` | After an outage, is a late run still useful? Default is **one year**, so 70 schedules will stampede on recovery. Minutes for a frequent monitor; hours for a daily aggregation. |
| **Jitter** | `ScheduleSpec.setJitter(...)` | ~30 jobs currently fire between 23:00 and 02:00 onto a shared database. Anything on a round hour that has no exact-time requirement gets jitter. |
| **Run timeout** | `WorkflowOptions.setWorkflowRunTimeout(...)` | Sized at ~2× the job's p99. Without it a wedged run blocks every future run forever under `SKIP`. Add `setWorkflowExecutionTimeout` too if the job uses Continue-As-New. |

Note the exact API — this is where people get it wrong:

```java
SchedulePolicy.newBuilder()
    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)  // not setOverlapPolicy, not SKIP
    .setCatchupWindow(Duration.ofMinutes(5))
    .setPauseOnFailure(true)
    .build();
```

Also set paused state atomically at creation via `ScheduleState.setPaused(true)` rather than
`.createSchedule(...).pause()`, which is two RPCs with a window between them.

## 6. Pick a task queue tier — never invent a queue

There are four, and adding a fifth is an architectural decision, not a job-level one:

| Queue | Profile | Slots |
|---|---|---|
| `${temporal.taskqueue.light}` | sub-second to ~1 min, high frequency | many — work is cheap and turns over fast |
| `${temporal.taskqueue.standard}` | seconds to minutes. The default | moderate |
| `${temporal.taskqueue.heavy}` | multi-minute to multi-hour, chunked | **few** — each holds a DB connection for a long time |
| `${temporal.taskqueue.notification}` | outbound email / WhatsApp / HTTP | rate-limited to the provider's quota, not concurrency-limited |

A task queue is a **slot pool**. Slots are handed out first-come-first-served with no priority and are
held for the entire duration of the activity, so a queue shared between a 200 ms query and a
four-hour aggregation makes the fast job wait behind the slow one — silently, as schedule-to-start
latency rather than as an error. One queue per job does not fix that either; it just multiplies
pollers, threads and connections by the job count.

**Before assigning a tier:**

1. **Measure, don't guess.** What is the job's p95 duration, how many rows does it touch, does it
   call anything external? A job placed by assumption ends up starving something else.
2. **Assign by what it contends for**, not by domain or team. Two jobs from different domains hitting
   the same database contend; two jobs in the same domain with different durations do not belong
   together.
3. **Check what is already on that tier.** Adding a 40-minute job to `heavy` consumes a slot for 40
   minutes — work out whether the tier's existing jobs still fit in the overnight window
   (`slots × pods × window` in activity-hours vs the sum of durations). Adding slots is the move that
   hurts the shared database, so prefer shortening the job or widening the window.
4. **Split the activity, not the workflow, when profiles differ within one job.** A job that queries
   then emails belongs on `standard` with its send activity routed to `notification` via
   `ActivityOptions.setTaskQueue(...)` — not on a queue of its own.

If a job genuinely fits none of the four, that is a conversation about a fifth tier, not a private
queue.

## 7. Declaring and changing a schedule

Every schedule is declared in `src/main/resources/schedules.yml`. There is no `@Configuration` class
per job any more, and `ScheduleReconciler` creates whatever is declared but missing on startup.

```yaml
- name: passbook-mismatch          # derives passbook-mismatch-schedule and -workflow
  workflow: com.yesmadam.schedulerservice.jobs.passbookmismatch.workflow.PassbookMismatchWorkflow
  tier: standard                   # must match the workflow's @WorkflowImpl annotation
  cron: "*/30 * * * *"             # FIVE fields, no '?'
  enabled: false                   # applies at creation only
  owner: finance
  description: ...
```

Anything omitted inherits `temporal.schedule.defaults.*`. Inheriting is fine; inheriting without
having thought about it is what rule 5 is about.

### Changing an existing schedule needs a manual delete

**Registration is create-only.** Editing a cron, tier or policy for a job that already exists in
Temporal does nothing — the log says `event=schedule_exists action=left_unchanged` and the old
definition keeps running. To apply a change:

1. Edit `schedules.yml` and merge.
2. **Delete the schedule in the Temporal UI.**
3. Deploy, or wait for the next pod start. It is recreated from configuration.
4. **Unpause it.** A recreated schedule comes back in its declared `enabled` state, and everything
   is currently declared `enabled: false`.

Step 4 is the one that gets missed, and missing it is silent: the job simply stops running, which
looks identical to a job that is idle. If a change did not take effect, check whether the schedule is
paused before checking anything else.

Deleting a schedule does not delete the workflow executions it already started.

### Things that will not do what you expect

- **Renaming `name`** does not rename the schedule. It orphans the old one and creates a new one, so
  the job runs from two schedules until you delete the original. The orphan is reported at startup as
  `event=schedule_orphans_found`, never deleted automatically.
- **A tier that disagrees with the workflow's `@WorkflowImpl`** produces a schedule dispatching to a
  queue where that workflow type is not registered. Nothing errors; tasks accumulate and nothing
  runs. `WorkflowTierAgreementTest` fails the build on this, so do not silence it.
- **A six-field Spring cron** is rejected at startup, and by `SchedulesYamlTest`. It is rejected
  rather than translated because a six-field expression can parse into a valid but *different*
  schedule — the job would run, at the wrong time. Translations for the incoming jobs are in
  `docs/temporal-migration/job-inventory.md`.
- **`temporal.schedule.defaults.create-paused`** has no effect on jobs declared here; `enabled`
  always wins. It governs the programmatic path only.
- **An empty job list** is logged at error, not warn. It almost always means `schedules.yml` did not
  load rather than that there is nothing to do.

### One-off still outstanding

The five schedules already live in Temporal were created before task queue tiering and still point at
the old per-job queues. Workers no longer poll those. **They must each be deleted once** so the next
startup recreates them on the tiered queues — until then they fire into queues nobody serves. Check
for `Running` executions on the old queues before deleting, and terminate any stragglers: a workflow's
task queue is fixed when it starts, so an in-flight run does not move.

## 8. Do not add cron-timing bookkeeping

`tbl_cron_time` is legacy. **New jobs must not write it.**

Temporal already records start, end, status, failure detail, duration and retries, queryable through
visibility. The custom table duplicated that badly: it writes its end row from a `finally`, so it
recorded 119 consecutive failures as clean completions, and a failure in the bookkeeping activity can
fail a job whose work succeeded. It also costs two activities and ~8 history events per run.

If a business consumer needs numbers — rows processed, records tagged, error codes — write those from
the business activity **in the same transaction as the work**, so the count and the work commit
together. That is a business record, not an execution log.

## 9. Nothing unbounded crosses the activity boundary

Activity arguments and return values are serialized into workflow history. The limits are 2 MB per
payload, 50 MB / 51,200 events per history — and before any of those, a ~512 MiB heap (the container
has a 2 GiB limit and no `-Xmx`, so the JVM default is 25%).

- Never return JPA entities. Return IDs, a cursor, or a small DTO.
- Never `SELECT` without a bound. Page inside the activity and heartbeat per page.
- Prefer: activity paginates internally and returns a count; workflow holds only a cursor.

`RoseOrderEligibleSpPoolWorkflowImpl` and `SebalTaggingWorkflowImpl` are the reference pattern — the
workflow drives chunking, each chunk is one retryable activity, and the activity's retry boundary
matches the database transaction boundary.

## 10. Long activities heartbeat

Anything that can exceed ~1 minute calls `Activity.getExecutionContext().heartbeat(...)` and sets
`setHeartbeatTimeout(...)`. Heartbeats are how liveness is detected, how progress is checkpointed and
how cancellation is delivered — without one, a cancelled workflow flips to CANCELED while the
activity thread keeps running.

Note for shutdown: `setAllowActivityHeartbeatDuringShutdown` does not exist in SDK 1.31.0, and the
pod has ~60 s to shut down. Design long work as small resumable chunks rather than expecting a long
activity to drain on deploy.

## 11. A plain exception in workflow code does not fail the workflow

This is the least intuitive rule here, and the failure mode is invisible.

`WorkflowImplementationOptions.setFailWorkflowExceptionTypes(...)` decides which exceptions escaping
a workflow method actually **fail the execution**. The default set is `TemporalFailure` subtypes and
`Error`. Everything else — a `NullPointerException` on an activity result, an `ArithmeticException`
from a ratio, a bare `RuntimeException` from a validation branch — is treated by the SDK as a bug you
are about to hot-fix. So it fails the **workflow task**, which retries indefinitely.

What that looks like: the execution never reaches a terminal state. It sits `Running`, history
filling with `WorkflowTaskFailed`, no failure, no alert, `pause-on-failure` never triggered. Compose
that with `SKIP` overlap and **every subsequent tick of that schedule is silently dropped** — which
is exactly the shape that hid 119 consecutive passbook failures. Only the run timeout bounds it.

An activity that exhausts its retries is *not* affected: the SDK wraps the cause in `ActivityFailure`,
which is a `TemporalFailure`, and propagates normally. This rule is only about exceptions thrown by
workflow code itself.

For scheduled jobs the hot-fix window is worth nothing — the next tick is the retry — so the right
setting is `failWorkflowExceptionTypes = Throwable.class`.

**It is not set as of 2026-08-29.** No Spring property reaches it: the starter exposes 21 keys and
none of them binds `WorkflowImplementationOptions`. The only supported route is a
`WorkflowImplementationOptionsCustomizer` bean, which the starter discovers and applies to every
workflow as the worker registers it. Until that bean exists, assume any exception your workflow code
can throw will wedge the run rather than fail it — so guard the arithmetic and the null paths in
workflow code yourself, and keep logic that can throw inside activities where `ActivityFailure`
propagates properly.

## 12. A job is not done until its service layer is tested

Coverage here is deliberately lopsided, and knowing which half you are in matters more than the
number. As of 2026-08-29 the whole service is **28% instruction / 16% branch**, but that average
hides the real shape:

- `packageexpiry` (workflow, activity, service) and `roseorder.workflow` are at ~100%.
- `sebaltagging.service` (736 instructions), `roseorder.service` (514) and `sebaltagging.repository`
  (488) are at **0%** — the two heaviest jobs in the service, whose business logic is entirely
  unexercised.
- `common.mail` is at 5% and `common.whatsapp.service` at 0% — the two outbound paths, and the ones
  that have actually been failing.

Test the **service layer**, not the workflow. Rule 5 of the migration checklist keeps business logic
free of `io.temporal` imports precisely so it can be tested with plain JUnit and no test environment.
A workflow test using `TestWorkflowEnvironment` is worth writing once per *shape* of orchestration
(chunked loop, fan-out, single call) — not once per job.

What a new job needs before merge:

- the service layer's branches, including the empty-result and partial-failure paths
- one test proving the activity returns a bounded payload (rule 9) — a count or a cursor, not a list
  that grows with the table
- for a chunked job, one test that a mid-run chunk failure does not lose or double-process the
  chunks either side of it

## 13. Reads and writes go to different databases

`ReadWriteRoutingDataSource` picks the pool from the transaction's read-only flag:
`@Transactional(readOnly = true)` gets the reader replica, everything else — **including code running
outside a transaction at all** — gets the writer. The writer is one size larger than the reader and
is shared with every other service on the instance, so an unannotated read is not a style problem.
It is load on the primary.

Three things to get right:

- **Annotate every read.** Spring Data marks derived `find*` methods read-only for you, but a
  `@Query`, a service method wrapping several repository calls, or anything using `EntityManager`
  directly does not inherit that. If it only reads, say so.
- **Put reads and writes on separate beans.** This is why `SebalTaggingReader` and
  `SebalTaggingWriter` exist. `@Transactional` is applied by a Spring proxy, and a call from one
  method to another *on the same bean* does not pass through the proxy — so a `readOnly = true`
  method invoked from its own class runs with the caller's transaction, silently on the writer. Two
  beans make the call cross the proxy boundary. Splitting them also makes the read/write shape of a
  job visible in its package listing.
- **Do not wrap a read and a write in one transaction** unless they must commit together. That
  transaction is not read-only, so the read goes to the writer too, and you have moved a scan onto
  the primary to get transactional guarantees you did not need.

`LazyConnectionDataSourceProxy` is load-bearing, not decoration: the transaction manager opens the
transaction before the read-only flag is visible to `TransactionSynchronizationManager`, so the
physical connection must be deferred to the first statement. Unwrap it and every query routes to the
writer, with no error and no visible change until the replica sits idle and the primary saturates.

### A transaction holds a connection for its whole life — keep it short

The arithmetic is the reason. Each pod has **30 writer and 30 reader connections**, and the four
worker tiers together offer **166 activity slots** (100 light + 40 standard + 6 heavy + 20
notification). That is roughly 5.5 activities per connection, which works only because most
activities hold a connection for milliseconds. A handful holding one for minutes and everything else
queues on connection acquisition — which surfaces as slowness in unrelated jobs, not as an error in
the job responsible.

So `@Transactional` goes on the smallest unit that must commit together, never on the method that
happens to be the entry point:

```java
// Wrong: holds a connection for the whole run — fetch, compute, format, write.
@Transactional
public void processEverything() { ... }

// Right: read, compute and format outside; the transaction covers only the write.
public void processEverything() {
  var rows = reader.load(window);          // read-only transaction, released immediately
  var deltas = compute(rows);              // no connection held at all
  writer.applyDeltas(deltas);              // @Transactional on this method only
}
```

Anything that is not a database operation — computing, sorting, formatting, an HTTP call, building an
email body — must sit outside the transaction. An outbound call inside one is the worst case: the
connection is pinned for the length of somebody else's timeout.

### Match the guarantee to the consequence

A transaction is not free, so ask what a partial write actually costs before reaching for one:

- **Append-only ledger or audit rows** — a duplicate row is noise, not corruption. It does not need a
  transaction wrapped around the whole operation; the insert alone is enough. Duplicates are
  identifiable later by `run_id`.
- **Counters, balances, tier assignments, anything a downstream decision reads** — a partial write is
  wrong data that nobody detects. These need the transaction, and they need the retry-safety in rule
  14 on top of it, because a transaction that commits and then loses its completion is applied twice.

The distinction is not how important the table sounds. It is whether applying the write twice, or
half of it, produces a value someone will act on.

### Replica lag is real

The reader is a replica. A row written by an earlier activity may not be visible to a read-only query
in the next one. Anything that writes and then verifies its own write must do both on the writer —
that is a legitimate reason to leave a read unannotated, and it needs a comment saying so, or someone
will "fix" it later.

## 14. An activity must survive being retried at 99% complete

Assume every activity is killed just before its last line and run again from the top. Retries do
this, and so does a worker restart mid-deploy. An activity that is only correct when it runs exactly
once is a bug that will not show up until the first bad night.

What that means in practice:

- **Commit per chunk, not per run.** `SebalTaggingWriter` is the reference: every public method is
  its own small `@Transactional` unit that commits independently, so a failure at chunk 400 of 500
  keeps the first 399 and the retry resumes rather than redoing them. Match the transaction boundary
  to the activity's retry boundary — a transaction spanning several activities cannot roll back to
  match a retry of one of them.
- **Track progress in the database, not in workflow memory.** The singleton watermark row
  (`tbl_sebal_tagging_watermark`) is what makes a resumed run know where it got to. A cursor held
  only in the workflow is lost on a worker restart.
- **Advance the cursor last.** The watermark moves only after the work it covers has committed. A
  run that dies mid-way leaves the watermark where it was and reprocesses that window — duplicated
  work, which is recoverable. Advance it first and the window is skipped forever, which is not.
- **Make writes converge, not accumulate — and check, because this one does not.**
  `SebalTaggingBatchDao.upsertTagDeltas` is the cautionary example, not the model. Its
  `ON DUPLICATE KEY UPDATE` reads
  `if(last_run_id <=> values(last_run_id), count + values(count), values(count))` in FULL mode and
  plain `count = count + values(count)` in INCREMENTAL. The `last_run_id` guard makes a row written
  by an *older* run get replaced rather than added to — which is what it was written for — but it
  does nothing about **this** run rewriting its own chunk, because `last_run_id` already matches. In
  both modes, applying the same chunk twice inside one run double-counts.

  The transaction is not the protection: if it rolls back, nothing is applied and the retry is clean.
  The exposure is that the transaction **commits and the activity then fails before Temporal records
  completion** — a worker killed mid-deploy, a heartbeat timeout, a lost connection on the way back.
  Temporal retries an activity it has no completion for, and the counts move twice.

  When a write cannot be made naturally convergent, record what has already been applied — a
  `(runId, chunkId)` marker row written in the same transaction — and make the write a no-op when its
  marker is already there. `runId` on the data alone is a provenance tag, not an idempotency key.
- **Send outbound messages last, and once.** Mail and WhatsApp cannot be rolled back. An activity
  that queries, writes and then emails will re-email on every retry. Put the send in its own activity,
  after the write commits, and give it a non-retryable classification for permanent failures (rule 4)
  so a bad recipient does not produce three identical messages to everyone else.

The question to ask of any activity before merging it: *if this runs twice, what does a customer
see?* If the answer is two messages, two credits or two rows, it is not finished.

## 15. Every query declares the index it relies on, and reports its own time

The production MySQL is shared and its buffer pool is fully committed. A query that falls off an
index does not just run slowly — it evicts other services' pages on the way past.

- **State the access path in the javadoc.** Which index, and whether it is a range scan, a covering
  lookup or a full scan. If the index does not exist yet, say so and give the `ALTER TABLE` — as
  `SpSebalSubCategoryTagLedgerRepository` does for its `run_id` full scan. A known full scan with a
  written-down remedy is a manageable debt; an unlabelled one is found during an incident.
- **Never feed a large id list into a follow-up query.** `SebalTaggingCohortRepository` documents the
  measurement: past roughly 20k ids, an `IN (...)` list pushed the optimizer off
  `tbl_order_service.order_id` and onto a full scan — a flat ~104s per chunk regardless of chunk
  size, 85% of the run. Folding the join into the first query made the same chunk 3.4s. If a second
  query would be keyed on the first one's output, it belongs in the first one.
- **Bound every scan by the chunk window**, never by "the job only runs at night".

### Timing — use `QueryTimer`

`utils/QueryTimer` is the convention here, already used by passbook-mismatch, rose-order and
sebal-tagging. It records **duration and row count together** under a query name, which is the pairing
rule 15 is about:

```java
QueryTimer timer = new QueryTimer();
List<PassbookMismatchProjection> found =
    timer.time("findMismatches", () -> mismatchRepository.findMismatches(windowStart, windowEnd));
```

Two things it expects of you:

- **A fresh instance per chunk.** Never static, never a field on the service. The service beans are
  singletons and chunks are processed concurrently by different activity executions, so a shared
  timer mixes results from different chunks into one map — and the numbers look plausible while being
  wrong, which is worse than having none.
- **Return the numbers, do not just collect them.** Every current user passes
  `timer.getDurationsMs()` and `timer.getRowCounts()` into its result DTO —
  `SebalTaggingChunkResult`, `RoseOrderChunkResult`, and passbook's row batch. That carries the
  timings across the activity boundary into workflow history, so per-run, per-chunk query times are
  readable in the Temporal UI with no metrics backend involved. A timer whose output stays inside the
  activity has measured nothing anyone can see.

There are two `time` overloads, for `Map`-returning and `List`-returning DAO calls; `ListSupplier`
exists only so the two do not collide under generic erasure. If you add a third return shape, add an
overload rather than unwrapping the call at the call site.

Keep the maps to one entry per named query. They travel in workflow history (rule 9), so a key per
row would be an unbounded payload wearing a diagnostic's clothing.

What this does not give you *yet* is a trend across runs — it answers "why was last night slow", not
"has this query been drifting for a month".

The intended fix is to extend `QueryTimer` itself rather than add a second mechanism at the call
sites: the same `time(...)` call records into the maps as it does today **and** publishes a
Micrometer timer, so every existing caller gains trends without changing a line, and there is no way
to instrument one and forget the other. `micrometer-registry-prometheus` and `/actuator/prometheus`
are already wired.

Two things to settle when that happens, noted here so they are not rediscovered:

- **`QueryTimer` is a plain object, constructed per chunk with `new` — not a Spring bean.** A
  `MeterRegistry` has to reach it explicitly, most cleanly as a constructor argument passed from the
  service that already has one injected. Reaching for a static registry holder instead would make it
  untestable and reintroduce the shared-state problem the per-chunk rule exists to avoid.
- **Tag on the query name only**, which is already a bounded set of string literals. Never on chunk
  index, run id or window dates — see the cardinality warning above.

Until then, per-run timings in workflow history are the whole story, and the scrape path for
`/actuator/prometheus` has not been verified end to end — worth one `curl` before anyone builds a
dashboard on it.

These numbers are also what rule 6 needs to place a job on a tier, and what rule 5 needs to size a run
timeout — a job with no timing history cannot be tiered by anything except guesswork.

---

## Migrating a job from cron / messagingapis / tracking

1. **Translate the cron.** Spring is 6-field (seconds first); Temporal is 5-field. `?` is Quartz-only
   and invalid. Prefer `ScheduleCalendarSpec` over a translated string — a one-position slip is a
   job running hours from where it should, and nothing validates it. Per-job translations are in
   `docs/temporal-migration/job-inventory.md`.
2. **Decide the timezone explicitly.** 77 of the incoming jobs declare no `zone=` and inherit
   whatever the source pod had.
3. **Check whether it should exist.** 44 of the source annotations are commented out — establish
   whether each was retired deliberately or disabled during an incident and forgotten.
4. **Annotate reads.** The source repos use Hibernate `SessionFactory` directly with no
   `@Transactional(readOnly = true)`. Without it, reads route to the **writer** here.
5. **Keep business logic out of Temporal types.** Activity is a thin adapter; the service layer
   imports nothing from `io.temporal`. That is what makes the logic testable and the job movable.
6. **Apply rules 1–15** before opening the PR.

## Known-wrong documentation

`README.md` describes `TemporalConfig.java` and `TemporalWorkerConfig.java` and tells you to register
workers there. Those files no longer exist — workers come from `@WorkflowImpl` / `@ActivityImpl`
auto-discovery. It also claims SDK 1.22.0; the POM pins 1.31.0. Trust this file over that one.
