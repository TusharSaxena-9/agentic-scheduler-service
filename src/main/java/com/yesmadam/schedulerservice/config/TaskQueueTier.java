package com.yesmadam.schedulerservice.config;

/**
 * The task queue tiers a job may be assigned to, split by resource-contention profile rather than by
 * domain or team.
 *
 * <p>A task queue is a slot pool, and slots are handed out first-come-first-served with no priority.
 * A queue shared between a 200 ms query and a four-hour aggregation makes the fast job wait behind
 * the slow one — silently, as schedule-to-start latency rather than as an error. Grouping by profile
 * keeps those pools separate and lets each be sized for what it actually contends for.
 *
 * <p>Declared as an enum so an unknown tier in {@code schedules.yml} fails at binding with a clear
 * message, rather than being validated by hand or — worse — resolving to a queue no worker polls.
 * The queue <em>name</em> deliberately lives in {@code temporal.taskqueue.*} instead of here: three
 * places consume it (the {@code @WorkflowImpl}/{@code @ActivityImpl} annotations, the
 * {@code spring.temporal.workers[*]} capacity blocks, and schedule registration), and it should have
 * one source.
 *
 * <p>Adding a fifth tier is an architectural decision, not a job-level one. See rule 6 in
 * {@code CLAUDE.md}.
 */
public enum TaskQueueTier {

  /** Sub-second to ~1 minute, high frequency. Many slots — the work is cheap and turns over fast. */
  LIGHT,

  /** Seconds to minutes. The default landing place for a new job. */
  STANDARD,

  /**
   * Multi-minute to multi-hour, chunked. Few slots: each holds a database connection for a long time
   * against an instance shared with the rest of the platform.
   */
  HEAVY,

  /**
   * Outbound email, WhatsApp and HTTP. Contends with a third-party quota rather than with MySQL, so
   * it wants a rate limit rather than a concurrency limit.
   */
  NOTIFICATION
}
