# Scheduler Service — Feature-Based Structure

Scheduler Service using Temporal with a feature-based package layout.

## Structure

- src/main/java/com/example/scheduler/config — Temporal clients and workers
- src/main/java/com/example/scheduler/{booking,payment,reminder} — Each feature contains model, dao, activity, workflow, schedule

## Adding a New Cron Job Checklist

1. Create `report/` package with model, dao, activity, workflow, schedule
2. Add to `application.properties`:
   - `temporal.taskqueue.report=report-task-queue`
   - `temporal.schedule.report=report-schedule`
   - `temporal.cron.report=0 8 * * *`
3. Add one `@Bean` in `TemporalWorkerConfig.java`:

```
@Bean
public Worker reportWorker(WorkerFactory factory, ReportActivities reportActivities) {
    Worker worker = factory.newWorker(reportTaskQueue);
    worker.registerWorkflowImplementationTypes(ReportWorkflowImpl.class);
    worker.registerActivitiesImplementations(reportActivities);
    return worker;
}
```

4. Done — nothing else changes
