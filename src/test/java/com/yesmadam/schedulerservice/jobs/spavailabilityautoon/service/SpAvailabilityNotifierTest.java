package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEvent;
import com.yesmadam.schedulerservice.common.clevertap.service.CleverTapEventService;

/**
 * Pins the CleverTap payload. The campaign that renders this push reads {@code msgTitle} and {@code
 * msgBody} off the event, so these key names are a contract with something outside this repo — a
 * rename here produces a push that is accepted with a 200 and then displays nothing.
 */
@ExtendWith(MockitoExtension.class)
class SpAvailabilityNotifierTest {

  private static final String EVENT_NAME = "SP_Campaign_Push";
  private static final String TITLE = "Aap jobs ke liye LIVE hain";
  private static final String BODY =
      "Aapki KIT issue ho gayi hai. Aapki availability ON ho chuki hai.";

  @Mock CleverTapEventService cleverTapEventService;

  private SpAvailabilityNotifier notifier() {
    return new SpAvailabilityNotifier(cleverTapEventService, EVENT_NAME, TITLE, BODY);
  }

  @Test
  @DisplayName("no SPs means no HTTP call at all")
  void emptyListSendsNothing() {
    assertThat(notifier().notifyAvailabilityTurnedOn(List.of())).isZero();
    assertThat(notifier().notifyAvailabilityTurnedOn(null)).isZero();

    verifyNoInteractions(cleverTapEventService);
  }

  @Test
  @DisplayName("one event per SP, all in a single upload")
  void oneEventPerSpInOneCall() {
    when(cleverTapEventService.sendEvents(anyList())).thenReturn(3);

    assertThat(notifier().notifyAvailabilityTurnedOn(List.of(1, 2, 3))).isEqualTo(3);

    ArgumentCaptor<List<CleverTapEvent>> events = ArgumentCaptor.forClass(List.class);
    verify(cleverTapEventService).sendEvents(events.capture());
    assertThat(events.getValue())
        .hasSize(3)
        .extracting(CleverTapEvent::getIdentity)
        .containsExactly("1", "2", "3");
  }

  @Test
  @DisplayName("the event carries the identity, the campaign name and the two copy fields")
  void eventShape() {
    when(cleverTapEventService.sendEvents(anyList())).thenReturn(1);

    notifier().notifyAvailabilityTurnedOn(List.of(77));

    CleverTapEvent event = captureSingleEvent();
    assertThat(event.getIdentity()).isEqualTo("77");
    assertThat(event.getType()).isEqualTo("event");
    assertThat(event.getEvtName()).isEqualTo(EVENT_NAME);
    assertThat(event.getEvtData()).containsEntry("msgTitle", TITLE).containsEntry("msgBody", BODY);
  }

  @Test
  @DisplayName("evtData carries the copy and nothing else")
  void payloadIsExactlyTheTwoCopyFields() {
    when(cleverTapEventService.sendEvents(anyList())).thenReturn(1);

    notifier().notifyAvailabilityTurnedOn(List.of(77));

    // The SP is identified by the event's identity, not by a field inside evtData — anything extra
    // here is payload the campaign does not read and nobody maintains.
    assertThat(captureSingleEvent().getEvtData()).containsOnlyKeys("msgTitle", "msgBody");
  }

  @Test
  @DisplayName("copy comes from configuration, so changing it needs no rebuild")
  void copyIsInjectedNotHardcoded() {
    when(cleverTapEventService.sendEvents(anyList())).thenReturn(1);

    new SpAvailabilityNotifier(cleverTapEventService, "Other_Event", "Other title", "Other body")
        .notifyAvailabilityTurnedOn(List.of(5));

    CleverTapEvent event = captureSingleEvent();
    assertThat(event.getEvtName()).isEqualTo("Other_Event");
    assertThat(event.getEvtData())
        .containsEntry("msgTitle", "Other title")
        .containsEntry("msgBody", "Other body");
  }

  private CleverTapEvent captureSingleEvent() {
    ArgumentCaptor<List<CleverTapEvent>> events = ArgumentCaptor.forClass(List.class);
    verify(cleverTapEventService).sendEvents(events.capture());
    assertThat(events.getValue()).hasSize(1);
    return events.getValue().get(0);
  }
}
