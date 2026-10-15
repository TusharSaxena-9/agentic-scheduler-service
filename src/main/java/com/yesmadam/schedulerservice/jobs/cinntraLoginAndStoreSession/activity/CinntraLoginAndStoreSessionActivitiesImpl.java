package com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.activity;

import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.service.CinntraLoginAndStoreSessionService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter between Temporal and the service layer — all logic lives in {@link
 * CinntraLoginAndStoreSessionService}.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.heavy}")
public class CinntraLoginAndStoreSessionActivitiesImpl implements CinntraLoginAndStoreSessionActivities {

  private final CinntraLoginAndStoreSessionService cinntraLoginAndStoreSessionService;

  public CinntraLoginAndStoreSessionActivitiesImpl(
      CinntraLoginAndStoreSessionService cinntraLoginAndStoreSessionService) {
    this.cinntraLoginAndStoreSessionService = cinntraLoginAndStoreSessionService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "cinntra-login-and-store-session")
  @HandleExceptions
  public void loginAndStoreSession() {
    cinntraLoginAndStoreSessionService.loginAndStoreSession();
  }
}
