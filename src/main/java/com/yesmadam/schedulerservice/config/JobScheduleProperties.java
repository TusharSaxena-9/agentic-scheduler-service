package com.yesmadam.schedulerservice.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * The declared set of scheduled jobs, bound from {@code schedules.yml}.
 *
 * <p>This list is the source of truth for what should exist in Temporal. Anything in it is created
 * or reconciled on startup; anything not in it is left alone, including schedules created
 * programmatically or by hand.
 *
 * @see ScheduleReconciler
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "scheduler")
public class JobScheduleProperties {

  /** Whether to reconcile schedules on startup at all. */
  private boolean reconcileOnStartup = true;

  /** The declared jobs. */
  private List<JobDefinition> jobs = new ArrayList<>();
}
