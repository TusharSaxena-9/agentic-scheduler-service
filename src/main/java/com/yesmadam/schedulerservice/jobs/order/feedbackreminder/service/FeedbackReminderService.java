package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import com.yesmadam.enumu.OrderStatus;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderFetchResult;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderStage;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.PendingFeedbackOrder;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.projections.PendingFeedbackOrderProjection;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.repository.FeedbackReminderOrderRepository;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.whatsapp.FeedbackReminderWhatsappService;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
public class FeedbackReminderService {

  /** Coupled to the {@code feedback-reminder} cron in schedules.yml — keep both in sync. */
  static final Duration TICK_INTERVAL = Duration.ofMinutes(5);

  private static final DateTimeFormatter BOOKING_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

  /**
   * Business-day zone for turning the precise window into a {@code booking_date} pre-filter (see
   * {@link FeedbackReminderOrderRepository#findCompletedOrdersPendingFeedback}) — CLAUDE.md rule 1:
   * always an explicit zone, never the JVM default.
   */
  private static final ZoneId BOOKING_DATE_ZONE = ZoneId.of("Asia/Kolkata");

  private final FeedbackReminderOrderRepository repository;
  private final FeedbackReminderWhatsappService whatsappService;

  public FeedbackReminderService(
      FeedbackReminderOrderRepository repository, FeedbackReminderWhatsappService whatsappService) {
    this.repository = repository;
    this.whatsappService = whatsappService;
  }

  @Transactional(readOnly = true)
  public FeedbackReminderFetchResult fetchPendingFeedbackOrders(
      long nominalFireTimeMillis, FeedbackReminderStage stage) {
    Instant nominalNow = Instant.ofEpochMilli(nominalFireTimeMillis);
    Instant windowEndInstant = nominalNow.minus(stage.getOffsetAfterCompletion());
    Instant windowStartInstant = windowEndInstant.minus(TICK_INTERVAL);
    Timestamp windowStart = Timestamp.from(windowStartInstant);
    Timestamp windowEnd = Timestamp.from(windowEndInstant);

    // Coarse booking_date pre-filter so the query can use the existing idx_order_status_booking_user
    // index instead of scanning every completed order ever (see the repository javadoc — verified on
    // UAT: ~2.46M rows examined without this, 6 with it). +/-1 day covers a booking that runs past
    // midnight; booking_date never determines correctness, only how many candidate rows are scanned.
    LocalDate windowStartDate = windowStartInstant.atZone(BOOKING_DATE_ZONE).toLocalDate();
    LocalDate windowEndDate = windowEndInstant.atZone(BOOKING_DATE_ZONE).toLocalDate();
    Date bookingDateLower = Date.valueOf(windowStartDate.minusDays(1));
    Date bookingDateUpper = Date.valueOf(windowEndDate.plusDays(2));

    QueryTimer timer = new QueryTimer();
    List<PendingFeedbackOrderProjection> found =
        timer.time(
            "findCompletedOrdersPendingFeedback",
            () ->
                repository.findCompletedOrdersPendingFeedback(
                    OrderStatus.ORDER_COMPLETED.getStatus(),
                    bookingDateLower,
                    bookingDateUpper,
                    windowStart,
                    windowEnd));

    List<PendingFeedbackOrder> orders =
        found.stream().map(this::toDto).collect(Collectors.toList());

    log.info(
        "event=feedback_reminder_scanned stage={} window_start={} window_end={} pending={} "
            + "durations_ms={} row_counts={}",
        stage,
        windowStart,
        windowEnd,
        orders.size(),
        timer.getDurationsMs(),
        timer.getRowCounts());

    return new FeedbackReminderFetchResult(
        stage, windowStart, windowEnd, orders, timer.getDurationsMs(), timer.getRowCounts());
  }

  public int sendFeedbackReminders(FeedbackReminderFetchResult fetchResult, String templateName) {
    List<PendingFeedbackOrder> orders = fetchResult.getOrders();
    if (ObjectUtils.isEmpty(orders)) {
      log.info(
          "event=feedback_reminder_send_skipped stage={} reason=no_pending_orders",
          fetchResult.getStage());
      return 0;
    }

    int sent = 0;
    for (PendingFeedbackOrder order : orders) {
      try {
        whatsappService.sendFeedbackReminderTemplate(
            String.valueOf(order.getMobile()),
            templateName,
            customerLabel(order),
            List.of(
                serviceLabel(order),
                String.valueOf(order.getOrderId()),
                formatOrderStartTime(order.getOrderStartTime())),
            order.getOrderId());
        sent++;
      } catch (IllegalStateException configEx) {
        // Not a per-recipient problem (e.g. rate-now.base-url unset) — it fails identically for
        // every remaining order too. Let it propagate so the activity fails loudly and once
        // (CLAUDE.md rule 21), instead of quietly logging N identical warnings and returning as if
        // this were an ordinary partial-failure batch.
        throw configEx;
      } catch (RuntimeException ex) {
        log.error(
            "event=feedback_reminder_send_failed stage={} order_id={} error={}",
            fetchResult.getStage(),
            order.getOrderId(),
            ex.getMessage());
      }
    }

    log.info(
        "event=feedback_reminder_sent stage={} pending={} sent={}",
        fetchResult.getStage(),
        orders.size(),
        sent);
    return sent;
  }

  private String customerLabel(PendingFeedbackOrder order) {
    return StringUtils.hasText(order.getCustomerName()) ? order.getCustomerName() : "there";
  }

  private String serviceLabel(PendingFeedbackOrder order) {
    return StringUtils.hasText(order.getServiceNames()) ? order.getServiceNames() : "YesMadam";
  }

  private String formatOrderStartTime(Timestamp orderStartTime) {
    return orderStartTime == null
        ? ""
        : orderStartTime.toLocalDateTime().toLocalDate().format(BOOKING_DATE_FORMAT);
  }

  private PendingFeedbackOrder toDto(PendingFeedbackOrderProjection p) {
    return new PendingFeedbackOrder(
        p.getOrderId(), p.getMobile(), p.getCustomerName(), p.getOrderStartTime(), p.getServiceNames());
  }
}
