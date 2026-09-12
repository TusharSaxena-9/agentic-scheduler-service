package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.activity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderFetchResult;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderStage;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.service.FeedbackReminderService;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter between Temporal and the service layer — all logic lives in {@link
 * FeedbackReminderService}.
 *
 * <p>Both activities stay on {@code light}, the same queue as the workflow, rather than routing the
 * send to {@code notification}: that queue name is only known as a Spring-resolved property
 * placeholder ({@code ${temporal.taskqueue.notification}}), and workflow code cannot use {@code
 * @Value} or read the Spring environment to pick it per-method (CLAUDE.md rule 1). See {@code
 * ProductHubLiveStockAlertActivitiesImpl} for the same reasoning. The fetch query is bounded to one
 * 5-minute tile and the send is a handful of HTTP calls, so both are cheap enough for {@code light}
 * regardless.
 *
 * <p>WhatsApp template name is injected here via {@code @Value} rather than in the workflow, since
 * workflow code cannot use Spring injection (CLAUDE.md rule 1).
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.light}")
public class FeedbackReminderActivitiesImpl implements FeedbackReminderActivities {

  @Value("${feedbackreminder.whatsapp.template.name}")
  private String whatsappTemplate;

  private final FeedbackReminderService feedbackReminderService;

  public FeedbackReminderActivitiesImpl(FeedbackReminderService feedbackReminderService) {
    this.feedbackReminderService = feedbackReminderService;
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "fetch-pending-feedback-orders")
  @HandleExceptions
  public FeedbackReminderFetchResult fetchPendingFeedbackOrders(
      long nominalFireTimeMillis, FeedbackReminderStage stage) {
    return feedbackReminderService.fetchPendingFeedbackOrders(nominalFireTimeMillis, stage);
  }

  /** {@inheritDoc} */
  @Override
  @Loggable(operation = "send-feedback-reminders")
  @HandleExceptions
  public int sendFeedbackReminders(FeedbackReminderFetchResult fetchResult) {
    return feedbackReminderService.sendFeedbackReminders(fetchResult, whatsappTemplate);
  }
}
