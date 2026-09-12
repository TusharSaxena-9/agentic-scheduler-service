package com.yesmadam.schedulerservice.jobs.order.feedbackreminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.enumu.OrderStatus;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderFetchResult;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.FeedbackReminderStage;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.model.PendingFeedbackOrder;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.projections.PendingFeedbackOrderProjection;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.repository.FeedbackReminderOrderRepository;
import com.yesmadam.schedulerservice.jobs.order.feedbackreminder.whatsapp.FeedbackReminderWhatsappService;

@ExtendWith(MockitoExtension.class)
class FeedbackReminderServiceTest {

  @Mock FeedbackReminderOrderRepository repository;
  @Mock FeedbackReminderWhatsappService whatsappService;
  @InjectMocks FeedbackReminderService service;

  // ================================================================
  // fetchPendingFeedbackOrders
  // ================================================================

  @Test
  void fetchPendingFeedbackOrders_shouldQueryExactTickWideWindowForStage() {
    Instant nominalNow = Instant.parse("2026-09-03T10:00:00Z");
    long nominalFireTimeMillis = nominalNow.toEpochMilli();

    when(repository.findCompletedOrdersPendingFeedback(anyInt(), any(), any(), any(), any()))
        .thenReturn(Collections.emptyList());

    service.fetchPendingFeedbackOrders(nominalFireTimeMillis, FeedbackReminderStage.THREE_HOURS);

    Timestamp expectedEnd = Timestamp.from(nominalNow.minus(3, ChronoUnit.HOURS));
    Timestamp expectedStart =
        Timestamp.from(nominalNow.minus(3, ChronoUnit.HOURS).minus(FeedbackReminderService.TICK_INTERVAL));
    // 2026-09-03T06:55:00Z/07:00:00Z is 2026-09-03 in IST either way, so the +/-1 day buffer
    // around the same booking_date resolves to 2026-09-02 (inclusive) .. 2026-09-05 (exclusive).
    Date expectedBookingDateLower = Date.valueOf("2026-09-02");
    Date expectedBookingDateUpper = Date.valueOf("2026-09-05");

    verify(repository)
        .findCompletedOrdersPendingFeedback(
            OrderStatus.ORDER_COMPLETED.getStatus(),
            expectedBookingDateLower,
            expectedBookingDateUpper,
            expectedStart,
            expectedEnd);
  }

  @Test
  void fetchPendingFeedbackOrders_shouldMapProjectionsToDtos() {
    Timestamp orderStartTime = Timestamp.valueOf("2026-09-01 10:30:00");
    PendingFeedbackOrderProjection projection =
        buildProjection(101L, 9876543210L, "Priya", orderStartTime, "Facial, Manicure");
    when(repository.findCompletedOrdersPendingFeedback(anyInt(), any(), any(), any(), any()))
        .thenReturn(List.of(projection));

    FeedbackReminderFetchResult result =
        service.fetchPendingFeedbackOrders(
            Instant.now().toEpochMilli(), FeedbackReminderStage.TWENTY_FOUR_HOURS);

    assertThat(result.getStage()).isEqualTo(FeedbackReminderStage.TWENTY_FOUR_HOURS);
    assertThat(result.getOrders()).hasSize(1);
    PendingFeedbackOrder dto = result.getOrders().get(0);
    assertThat(dto.getOrderId()).isEqualTo(101L);
    assertThat(dto.getMobile()).isEqualTo(9876543210L);
    assertThat(dto.getCustomerName()).isEqualTo("Priya");
    assertThat(dto.getOrderStartTime()).isEqualTo(orderStartTime);
    assertThat(dto.getServiceNames()).isEqualTo("Facial, Manicure");
  }

  @Test
  void fetchPendingFeedbackOrders_shouldReturnEmptyListWhenNoneFound() {
    when(repository.findCompletedOrdersPendingFeedback(anyInt(), any(), any(), any(), any()))
        .thenReturn(Collections.emptyList());

    FeedbackReminderFetchResult result =
        service.fetchPendingFeedbackOrders(
            Instant.now().toEpochMilli(), FeedbackReminderStage.FORTY_EIGHT_HOURS);

    assertThat(result.getOrders()).isEmpty();
  }

  // ================================================================
  // sendFeedbackReminders
  // ================================================================

  @Test
  void sendFeedbackReminders_shouldSkipWhenNoPendingOrders() {
    FeedbackReminderFetchResult fetchResult =
        new FeedbackReminderFetchResult(
            FeedbackReminderStage.THREE_HOURS, new Timestamp(0), new Timestamp(0), Collections.emptyList());

    int sent = service.sendFeedbackReminders(fetchResult, "service_feedback_app_link");

    assertThat(sent).isZero();
    verifyNoInteractions(whatsappService);
  }

  @Test
  void sendFeedbackReminders_shouldSendNameServiceOrderIdAndFormattedDate() {
    PendingFeedbackOrder order =
        new PendingFeedbackOrder(
            202L, 9876543210L, "Priya", Timestamp.valueOf("2026-09-01 10:30:00"), "Facial, Manicure");
    FeedbackReminderFetchResult fetchResult =
        new FeedbackReminderFetchResult(
            FeedbackReminderStage.THREE_HOURS, new Timestamp(0), new Timestamp(0), List.of(order));

    int sent = service.sendFeedbackReminders(fetchResult, "service_feedback_app_link");

    assertThat(sent).isEqualTo(1);
    ArgumentCaptor<List<String>> dataCaptor = ArgumentCaptor.forClass(List.class);
    verify(whatsappService)
        .sendFeedbackReminderTemplate(
            eq("9876543210"),
            eq("service_feedback_app_link"),
            eq("Priya"),
            dataCaptor.capture(),
            eq(202L));
    assertThat(dataCaptor.getValue()).containsExactly("Facial, Manicure", "202", "01 Sep 2026");
  }

  @Test
  void sendFeedbackReminders_shouldFallBackToDefaultLabelsWhenBlank() {
    PendingFeedbackOrder order =
        new PendingFeedbackOrder(303L, 9876543210L, "", Timestamp.valueOf("2026-09-01 10:30:00"), "");
    FeedbackReminderFetchResult fetchResult =
        new FeedbackReminderFetchResult(
            FeedbackReminderStage.THREE_HOURS, new Timestamp(0), new Timestamp(0), List.of(order));

    service.sendFeedbackReminders(fetchResult, "service_feedback_app_link");

    ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> dataCaptor = ArgumentCaptor.forClass(List.class);
    verify(whatsappService)
        .sendFeedbackReminderTemplate(any(), any(), nameCaptor.capture(), dataCaptor.capture(), eq(303L));
    assertThat(nameCaptor.getValue()).isEqualTo("there");
    assertThat(dataCaptor.getValue().get(0)).isEqualTo("YesMadam");
  }

  @Test
  void sendFeedbackReminders_shouldPropagateConfigFailureRatherThanSwallowIt() {
    PendingFeedbackOrder first =
        new PendingFeedbackOrder(1L, 1111111111L, "A", Timestamp.valueOf("2026-09-01 10:30:00"), "Facial");
    PendingFeedbackOrder second =
        new PendingFeedbackOrder(2L, 2222222222L, "B", Timestamp.valueOf("2026-09-01 10:30:00"), "Facial");
    FeedbackReminderFetchResult fetchResult =
        new FeedbackReminderFetchResult(
            FeedbackReminderStage.THREE_HOURS,
            new Timestamp(0),
            new Timestamp(0),
            List.of(first, second));

    doThrow(new IllegalStateException("feedbackreminder.whatsapp.rate-now.base-url is not set"))
        .when(whatsappService)
        .sendFeedbackReminderTemplate(any(), any(), any(), any(), eq(1L));

    assertThatThrownBy(() -> service.sendFeedbackReminders(fetchResult, "service_feedback_app_link"))
        .isInstanceOf(IllegalStateException.class);
    // Fails on the first order and never attempts the second — a config problem is not isolatable
    // per recipient, unlike a genuine provider/network failure.
    verify(whatsappService, never()).sendFeedbackReminderTemplate(any(), any(), any(), any(), eq(2L));
  }

  @Test
  void sendFeedbackReminders_shouldIsolateOneOrdersSendFailureFromTheRest() {
    PendingFeedbackOrder failing =
        new PendingFeedbackOrder(1L, 1111111111L, "A", Timestamp.valueOf("2026-09-01 10:30:00"), "Facial");
    PendingFeedbackOrder succeeding =
        new PendingFeedbackOrder(2L, 2222222222L, "B", Timestamp.valueOf("2026-09-01 10:30:00"), "Facial");
    FeedbackReminderFetchResult fetchResult =
        new FeedbackReminderFetchResult(
            FeedbackReminderStage.THREE_HOURS,
            new Timestamp(0),
            new Timestamp(0),
            List.of(failing, succeeding));

    doThrow(new RuntimeException("provider outage"))
        .when(whatsappService)
        .sendFeedbackReminderTemplate(
            eq("1111111111"),
            any(),
            eq("A"),
            eq(List.of("Facial", "1", "01 Sep 2026")),
            eq(1L));

    int sent = service.sendFeedbackReminders(fetchResult, "service_feedback_app_link");

    assertThat(sent).isEqualTo(1);
    verify(whatsappService)
        .sendFeedbackReminderTemplate(
            eq("2222222222"),
            any(),
            eq("B"),
            eq(List.of("Facial", "2", "01 Sep 2026")),
            eq(2L));
  }

  // ================================================================
  // Helpers
  // ================================================================

  private static PendingFeedbackOrderProjection buildProjection(
      Long orderId, Long mobile, String customerName, Timestamp orderStartTime, String serviceNames) {
    return new PendingFeedbackOrderProjection() {
      @Override
      public Long getOrderId() {
        return orderId;
      }

      @Override
      public Long getMobile() {
        return mobile;
      }

      @Override
      public String getCustomerName() {
        return customerName;
      }

      @Override
      public Timestamp getOrderStartTime() {
        return orderStartTime;
      }

      @Override
      public String getServiceNames() {
        return serviceNames;
      }
    };
  }
}
