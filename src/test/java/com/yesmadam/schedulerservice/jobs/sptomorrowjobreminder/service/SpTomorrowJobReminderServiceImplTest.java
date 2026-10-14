package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.yesmadam.schedulerservice.common.clevertap.CleverTapEventProducer;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEventPayload;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.model.SpTomorrowJobReminderBatch;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.projections.SpTomorrowJobProjection;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.repository.SpTomorrowJobReminderRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpTomorrowJobReminderServiceImplTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Mock SpTomorrowJobReminderRepository repository;
  @Mock CleverTapEventProducer cleverTapEventProducer;
  @InjectMocks SpTomorrowJobReminderServiceImpl service;

  private void givenConfig() {
    ReflectionTestUtils.setField(service, "eventName", "SP_Campaign_Push");
    ReflectionTestUtils.setField(service, "pushTitle", "Kal ki jobs ke products check karein");
    ReflectionTestUtils.setField(service, "pushBody", "Aur job se pehle ready rahein!");
    ReflectionTestUtils.setField(service, "deeplinkCode", 7);
  }

  // ================================================================
  // fetchTomorrowReminders
  // ================================================================

  @Test
  void fetchTomorrowReminders_shouldReturnDistinctSpIdsForTomorrow() {
    when(repository.findSpIdsWithJobOnBookingDate(any(), anyList()))
        .thenReturn(List.of(projection(101), projection(202)));

    SpTomorrowJobReminderBatch batch = service.fetchTomorrowReminders();

    assertThat(batch.getSpIds()).containsExactlyInAnyOrder(101, 202);
    assertThat(batch.getJobDate()).isEqualTo(LocalDate.now(IST).plusDays(1));
  }

  @Test
  void fetchTomorrowReminders_shouldDedupeSpIdAppearingInMultipleRows() {
    when(repository.findSpIdsWithJobOnBookingDate(any(), anyList()))
        .thenReturn(List.of(projection(101), projection(101), projection(202)));

    SpTomorrowJobReminderBatch batch = service.fetchTomorrowReminders();

    assertThat(batch.getSpIds()).containsExactlyInAnyOrder(101, 202);
  }

  @Test
  void fetchTomorrowReminders_shouldFilterOutNullSpIds() {
    when(repository.findSpIdsWithJobOnBookingDate(any(), anyList()))
        .thenReturn(List.of(projection(101), projection(null), projection(202)));

    SpTomorrowJobReminderBatch batch = service.fetchTomorrowReminders();

    assertThat(batch.getSpIds()).containsExactlyInAnyOrder(101, 202);
  }

  @Test
  void fetchTomorrowReminders_shouldQueryOnTomorrowsMidnightDate() {
    when(repository.findSpIdsWithJobOnBookingDate(any(), anyList())).thenReturn(List.of());
    ArgumentCaptor<Date> dateCaptor = ArgumentCaptor.forClass(Date.class);

    service.fetchTomorrowReminders();

    verify(repository).findSpIdsWithJobOnBookingDate(dateCaptor.capture(), anyList());
    assertThat(dateCaptor.getValue()).isEqualTo(Date.valueOf(LocalDate.now(IST).plusDays(1)));
  }

  @Test
  void fetchTomorrowReminders_shouldReturnEmptyBatchWhenNobodyHasAJobTomorrow() {
    when(repository.findSpIdsWithJobOnBookingDate(any(), anyList())).thenReturn(List.of());

    assertThat(service.fetchTomorrowReminders().getSpIds()).isEmpty();
  }

  @Test
  void fetchTomorrowReminders_shouldCarryQueryTimingAndRowCount() {
    when(repository.findSpIdsWithJobOnBookingDate(any(), anyList()))
        .thenReturn(List.of(projection(101)));

    SpTomorrowJobReminderBatch batch = service.fetchTomorrowReminders();

    assertThat(batch.getQueryDurationsMs()).containsKey("findSpIdsWithJobOnBookingDate");
    assertThat(batch.getQueryRowCounts()).containsEntry("findSpIdsWithJobOnBookingDate", 1);
  }

  // ================================================================
  // sendReminders
  // ================================================================

  @Test
  void sendReminders_shouldSkipPublishingWhenBatchIsEmpty() {
    givenConfig();
    SpTomorrowJobReminderBatch batch =
        new SpTomorrowJobReminderBatch(LocalDate.now(IST).plusDays(1), List.of());

    service.sendReminders(batch);

    verify(cleverTapEventProducer, never()).publish(anyList());
  }

  @Test
  void sendReminders_shouldPublishOneEventPerSpWithTheConfiguredEventName() {
    givenConfig();
    LocalDate jobDate = LocalDate.now(IST).plusDays(1);
    SpTomorrowJobReminderBatch batch = new SpTomorrowJobReminderBatch(jobDate, List.of(101, 202));
    ArgumentCaptor<List<CleverTapEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);

    service.sendReminders(batch);

    verify(cleverTapEventProducer).publish(eventsCaptor.capture());
    List<CleverTapEventPayload> events = eventsCaptor.getValue();
    assertThat(events).hasSize(2);
    assertThat(events)
        .extracting(CleverTapEventPayload::getIdentity)
        .containsExactlyInAnyOrder(101, 202);
    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(e.getEvtName()).isEqualTo("SP_Campaign_Push");
              assertThat(e.getEvtData())
                  .containsEntry("msgTitle", "Kal ki jobs ke products check karein")
                  .containsEntry("msgBody", "Aur job se pehle ready rahein!")
                  .containsEntry("deeplinkCode", 7);
            });
  }

  private SpTomorrowJobProjection projection(Integer spId) {
    return () -> spId;
  }
}
