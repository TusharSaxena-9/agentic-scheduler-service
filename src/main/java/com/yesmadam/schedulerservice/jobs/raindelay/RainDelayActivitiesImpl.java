package com.yesmadam.schedulerservice.jobs.raindelay;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter — all logic lives in {@link RainDelayService}.
 *
 * <p>Both on {@code standard}. Routing the send to {@code notification} would need that queue's
 * name in workflow code, where it is only available as a Spring placeholder. Same call as {@code
 * ProductHubLiveStockAlertActivitiesImpl}.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class RainDelayActivitiesImpl implements RainDelayActivities {

  private final RainDelayService rainDelayService;

  public RainDelayActivitiesImpl(RainDelayService rainDelayService) {
    this.rainDelayService = rainDelayService;
  }

  @Override
  @Loggable(operation = "resolve-rainy-hubs")
  @HandleExceptions
  public RainyHubResolution resolveRainyHubs(long nominalFireTimeMillis) {
    return rainDelayService.resolveRainyHubs(nominalFireTimeMillis);
  }

  @Override
  @Loggable(operation = "notify-rainy-hubs")
  @HandleExceptions
  public RainDelayNotifyResult notifyRainyHubs(RainyHubResolution resolution) {
    return rainDelayService.notifyRainyHubs(resolution);
  }
}
