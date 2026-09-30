package com.yesmadam.schedulerservice.jobs.raindelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEvent;
import com.yesmadam.schedulerservice.common.clevertap.service.CleverTapEventService;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;

/** The job's decisions, with no database and no HTTP. Covers both empty paths and partial failure. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RainDelayServiceTest {

  /** 2026-09-22 10:00 IST as epoch millis — the run whose tile is [10:30, 11:00). */
  private static final long TEN_AM =
      java.time.LocalDateTime.of(2026, 9, 22, 10, 0)
          .atZone(java.time.ZoneId.of("Asia/Kolkata"))
          .toInstant()
          .toEpochMilli();

  @Mock SettingService settingService;
  @Mock RainDelayReader reader;
  @Mock WeatherClient weatherClient;
  @Mock CleverTapEventService cleverTapEventService;

  @InjectMocks RainDelayService service;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "eventName", "Rain_Delay_Push");
    ReflectionTestUtils.setField(service, "pushTitle", "Rain in your area");
    ReflectionTestUtils.setField(service, "pushBody", "Your professional may be slightly delayed.");
    ReflectionTestUtils.setField(
        service, "deeplinkPrefix", "https://www.yesmadam.com/?deeplinkCode=27&orderId=");
  }

  // ---------------------------------------------------------------- resolveRainyHubs

  @Test
  @DisplayName("rainHub missing: no order query is issued at all")
  void notConfiguredSkipsEverything() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(Map.of());

    RainyHubResolution resolution = service.resolveRainyHubs(TEN_AM);

    assertThat(resolution.getConfiguredHubCount()).isZero();
    assertThat(resolution.getRainyHubIds()).isEmpty();
    // An empty IN () is a MySQL syntax error, and there is nothing to check anyway.
    verifyNoInteractions(reader);
    verifyNoInteractions(weatherClient);
  }

  @Test
  @DisplayName("rainHub blank is treated as not configured, not as 'every hub'")
  void blankRainHubIsNotConfigured() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("   "));

    assertThat(service.resolveRainyHubs(TEN_AM).getConfiguredHubCount()).isZero();
    verifyNoInteractions(reader);
  }

  @Test
  @DisplayName("rainHub tolerates spaces and drops non-numeric tokens")
  void rainHubParsingTolerance() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4, 5 ,10,, x ,12"));
    when(reader.findCandidateHubs(anyList(), any(), any())).thenReturn(List.of());

    RainyHubResolution resolution = service.resolveRainyHubs(TEN_AM);

    // Spaces trimmed, empty token skipped, non-numeric dropped rather than failing the whole row.
    assertThat(resolution.getConfiguredHubCount()).isEqualTo(4);

    ArgumentCaptor<List<Integer>> hubIds = ArgumentCaptor.forClass(List.class);
    verify(reader).findCandidateHubs(hubIds.capture(), any(), any());
    assertThat(hubIds.getValue()).containsExactly(4, 5, 10, 12);
  }

  @Test
  @DisplayName("configured hub with no imminent booking is never looked up")
  void noCandidateHubsSkipsWeatherLookup() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4,5"));
    when(reader.findCandidateHubs(anyList(), any(), any())).thenReturn(List.of());

    RainyHubResolution resolution = service.resolveRainyHubs(TEN_AM);

    assertThat(resolution.getCandidateHubCount()).isZero();
    // Billed per call: a hub with nothing booked in the window must not consume one.
    verifyNoInteractions(weatherClient);
  }

  @Test
  @DisplayName("the weather API is called once per candidate hub, never once per order")
  void oneLookupPerCandidateHub() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4,5,10"));
    when(reader.findCandidateHubs(anyList(), any(), any()))
        .thenReturn(
            List.of(
                candidate(4, "Dwarka", "28.61", "77.20", 120L),
                candidate(5, "Noida", "28.53", "77.39", 80L),
                candidate(10, "Gurgaon", "28.45", "77.02", 5L)));
    when(weatherClient.weatherAt(eq(4), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.RAINY);
    when(weatherClient.weatherAt(eq(5), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.CLEAR);
    when(weatherClient.weatherAt(eq(10), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.UNKNOWN);

    RainyHubResolution resolution = service.resolveRainyHubs(TEN_AM);

    // 205 bookings across three hubs, three HTTP calls.
    verify(weatherClient, org.mockito.Mockito.times(3))
        .weatherAt(anyInt(), anyString(), anyString(), anyDouble());
    assertThat(resolution.getRainyHubIds()).containsExactly(4);
  }

  @Test
  @DisplayName("a failed lookup does not read as clear weather")
  void unknownIsCountedSeparatelyFromClear() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4,5"));
    when(reader.findCandidateHubs(anyList(), any(), any()))
        .thenReturn(
            List.of(
                candidate(4, "Dwarka", "28.61", "77.20", 1L),
                candidate(5, "Noida", "bad-lat", "77.39", 1L)));
    when(weatherClient.weatherAt(eq(4), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.CLEAR);
    when(weatherClient.weatherAt(eq(5), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.UNKNOWN);

    RainyHubResolution resolution = service.resolveRainyHubs(TEN_AM);

    // Both send nothing, but only one is known dry — a boolean would hide the outage.
    assertThat(resolution.getRainyHubIds()).isEmpty();
    assertThat(resolution.getUnknownHubCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("the window is [scheduled+30m, scheduled+60m), from the nominal time")
  void windowIsReportedOnTheResult() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4"));
    when(reader.findCandidateHubs(anyList(), any(), any())).thenReturn(List.of());

    RainyHubResolution resolution = service.resolveRainyHubs(TEN_AM);

    ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reader).findCandidateHubs(anyList(), start.capture(), end.capture());

    // The 10:00 run covers [10:30, 11:00) — offset 30m from the scheduled time, 30m long.
    assertThat(start.getValue()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 22, 10, 30));
    assertThat(end.getValue()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 22, 11, 0));
    assertThat(resolution.getWindowStart()).isEqualTo("2026-09-22 10:30:00");
    assertThat(resolution.getWindowEnd()).isEqualTo("2026-09-22 11:00:00");
  }


  @Test
  @DisplayName("the precip threshold comes from settings and is passed to every lookup")
  void precipThresholdFromSettings() {
    Map<String, SettingsForEnalbeDisable> cfg = new LinkedHashMap<>(settings("4"));
    cfg.put(
        "RAIN_PRECIP_MM_THRESHOLD", setting("RAIN_PRECIP_MM_THRESHOLD", "7.5"));
    when(settingService.findSettingWithKeys(anyList())).thenReturn(cfg);
    when(reader.findCandidateHubs(anyList(), any(), any()))
        .thenReturn(List.of(candidate(4, "Dwarka", "28.61", "77.20", 1L)));
    when(weatherClient.weatherAt(anyInt(), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.CLEAR);

    service.resolveRainyHubs(TEN_AM);

    verify(weatherClient).weatherAt(eq(4), anyString(), anyString(), eq(7.5));
  }

  @Test
  @DisplayName("both settings keys are read in one query")
  void settingsReadOnce() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4"));
    when(reader.findCandidateHubs(anyList(), any(), any())).thenReturn(List.of());

    service.resolveRainyHubs(TEN_AM);

    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(settingService).findSettingWithKeys(keys.capture());
    assertThat(keys.getValue()).containsExactly("rainHub", "RAIN_PRECIP_MM_THRESHOLD");
  }

  @Test
  @DisplayName("a zero threshold falls back to the default instead of flagging every hub")
  void nonPositiveThresholdFallsBack() {
    Map<String, SettingsForEnalbeDisable> cfg = new LinkedHashMap<>(settings("4"));
    cfg.put("RAIN_PRECIP_MM_THRESHOLD", setting("RAIN_PRECIP_MM_THRESHOLD", "0"));
    when(settingService.findSettingWithKeys(anyList())).thenReturn(cfg);
    when(reader.findCandidateHubs(anyList(), any(), any()))
        .thenReturn(List.of(candidate(4, "Dwarka", "28.61", "77.20", 1L)));
    when(weatherClient.weatherAt(anyInt(), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.CLEAR);

    service.resolveRainyHubs(TEN_AM);

    // Zero would make every reading rain risk and message every customer at every configured hub.
    verify(weatherClient)
        .weatherAt(eq(4), anyString(), anyString(), eq(2.5));
  }

  @Test
  @DisplayName("an unparseable threshold falls back rather than failing the run")
  void unparseableThresholdFallsBack() {
    Map<String, SettingsForEnalbeDisable> cfg = new LinkedHashMap<>(settings("4"));
    cfg.put("RAIN_PRECIP_MM_THRESHOLD", setting("RAIN_PRECIP_MM_THRESHOLD", "heavy"));
    when(settingService.findSettingWithKeys(anyList())).thenReturn(cfg);
    when(reader.findCandidateHubs(anyList(), any(), any()))
        .thenReturn(List.of(candidate(4, "Dwarka", "28.61", "77.20", 1L)));
    when(weatherClient.weatherAt(anyInt(), anyString(), anyString(), anyDouble()))
        .thenReturn(HubWeather.CLEAR);

    service.resolveRainyHubs(TEN_AM);

    verify(weatherClient).weatherAt(eq(4), anyString(), anyString(), eq(2.5));
  }

  @Test
  @DisplayName("consecutive runs tile exactly — no gap, no overlap, boundary belongs to the later run")
  void consecutiveRunsTile() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4"));
    when(reader.findCandidateHubs(anyList(), any(), any())).thenReturn(List.of());

    long tenThirty = TEN_AM + java.time.Duration.ofMinutes(30).toMillis();
    RainyHubResolution first = service.resolveRainyHubs(TEN_AM);
    RainyHubResolution second = service.resolveRainyHubs(tenThirty);

    // [10:30, 11:00) then [11:00, 11:30). An order at exactly 11:00 belongs only to the second:
    // windowEnd is exclusive and the next windowStart is inclusive.
    assertThat(first.getWindowStart()).isEqualTo("2026-09-22 10:30:00");
    assertThat(first.getWindowEnd()).isEqualTo("2026-09-22 11:00:00");
    assertThat(second.getWindowStart()).isEqualTo("2026-09-22 11:00:00");
    assertThat(second.getWindowEnd()).isEqualTo("2026-09-22 11:30:00");
    assertThat(first.getWindowEnd()).isEqualTo(second.getWindowStart());
  }

  @Test
  @DisplayName("a late start does not slide the tile — the window is anchored to the nominal time")
  void lateStartDoesNotSlideWindow() {
    when(settingService.findSettingWithKeys(anyList())).thenReturn(settings("4"));
    when(reader.findCandidateHubs(anyList(), any(), any())).thenReturn(List.of());

    // Same nominal time, whatever the wall clock says when the activity actually runs. A window
    // built from now() would gap against the previous tile and lose every order in between.
    RainyHubResolution resolution = service.resolveRainyHubs(TEN_AM);

    assertThat(resolution.getWindowStart()).isEqualTo("2026-09-22 10:30:00");
    assertThat(resolution.getWindowEnd()).isEqualTo("2026-09-22 11:00:00");
  }

  @Test
  @DisplayName("the window length matches the cron period, or tiles would gap")
  void windowLengthMatchesCronPeriod() {
    // schedules.yml declares "*/30 * * * *". These must agree; the comment there says so too.
    assertThat(RainDelayService.WINDOW_LENGTH_MINUTES).isEqualTo(30);
    assertThat(RainDelayService.WINDOW_OFFSET_MINUTES).isEqualTo(30);
  }

  // ---------------------------------------------------------------- notifyRainyHubs

  @Test
  @DisplayName("no rainy hubs: nothing is queried and nothing is pushed")
  void noRainyHubsSendsNothing() {
    RainDelayNotifyResult result = service.notifyRainyHubs(dryResolution());

    assertThat(result.getTargets()).isZero();
    verifyNoInteractions(reader);
    verifyNoInteractions(cleverTapEventService);
  }










  @Test
  @DisplayName("the notify step reuses the resolved window rather than recomputing now()")
  void reusesResolvedWindow() {
    when(reader.findNotificationTargets(anyList(), any(), any())).thenReturn(List.of());

    service.notifyRainyHubs(rainyResolution());

    ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reader).findNotificationTargets(anyList(), start.capture(), end.capture());

    // Recomputing now() would shift the window by the weather lookup time.
    assertThat(start.getValue()).isEqualTo(LocalDateTime.of(2026, 9, 22, 14, 0, 0));
    assertThat(end.getValue()).isEqualTo(LocalDateTime.of(2026, 9, 22, 15, 0, 0));
  }

  @Test
  @DisplayName("a null resolution is handled rather than thrown on")
  void nullResolutionIsSafe() {
    assertThat(service.notifyRainyHubs(null).getTargets()).isZero();
  }

  @Test
  @DisplayName("the notify step issues no settings query — the cap rides on the resolution")
  void notifyDoesNotRereadSettings() {
    when(reader.findNotificationTargets(anyList(), any(), any())).thenReturn(List.of());

    service.notifyRainyHubs(rainyResolution());

    // One settings read per run, in the resolve step.
    verifyNoInteractions(settingService);
    verify(reader).findNotificationTargets(anyList(), any(), any());
  }



  @Test
  @DisplayName("one upload for the whole slot, identified by user_id")
  void pushesAsOneBatch() {
    when(reader.findNotificationTargets(anyList(), any(), any()))
        .thenReturn(List.of(target(101L, 4, 7701L), target(102L, 4, 7702L)));
    when(cleverTapEventService.sendUserEvents(anyList())).thenReturn(2);

    RainDelayNotifyResult result = service.notifyRainyHubs(rainyResolution());

    assertThat(result.getTargets()).isEqualTo(2);
    assertThat(result.getSent()).isEqualTo(2);

    ArgumentCaptor<List<CleverTapEvent>> events = ArgumentCaptor.forClass(List.class);
    // One HTTP call for both customers, not one per customer.
    verify(cleverTapEventService, org.mockito.Mockito.times(1)).sendUserEvents(events.capture());
    assertThat(events.getValue()).hasSize(2);
    assertThat(events.getValue().get(0).getIdentity()).isEqualTo("7701");
    assertThat(events.getValue().get(0).getEvtName()).isEqualTo("Rain_Delay_Push");
    assertThat(events.getValue().get(0).getType()).isEqualTo("event");
  }

  @Test
  @DisplayName("evtData carries the campaign's expected keys and the booking deeplink")
  void evtDataKeysAreStable() {
    when(reader.findNotificationTargets(anyList(), any(), any()))
        .thenReturn(List.of(target(123456L, 4, 7701L)));
    when(cleverTapEventService.sendUserEvents(anyList())).thenReturn(1);

    service.notifyRainyHubs(rainyResolution());

    ArgumentCaptor<List<CleverTapEvent>> events = ArgumentCaptor.forClass(List.class);
    verify(cleverTapEventService).sendUserEvents(events.capture());
    Map<String, Object> data = events.getValue().get(0).getEvtData();

    // These names are a contract with a campaign outside this repo. Rename one and CleverTap still
    // returns 200, but the push renders blank or the tap goes nowhere.
    assertThat(data)
        .containsExactly(
            Map.entry("orderId", 123456L),
            Map.entry("deeplinkCode", "27"),
            Map.entry("msgTitle", "Rain in your area"),
            Map.entry("msgBody", "Your professional may be slightly delayed."),
            Map.entry("deeplink", "https://www.yesmadam.com/?deeplinkCode=27&orderId=123456"));
  }

  @Test
  @DisplayName("targets are chunked below CleverTap's 1000-event upload ceiling")
  void chunksAtUploadCeiling() {
    List<RainDelayTargetProjection> rows =
        IntStream.range(0, 1001)
            .mapToObj(i -> target(200L + i, 4, 7700L + i))
            .collect(Collectors.toList());
    when(reader.findNotificationTargets(anyList(), any(), any())).thenReturn(rows);
    when(cleverTapEventService.sendUserEvents(anyList()))
        .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    RainDelayNotifyResult result = service.notifyRainyHubs(rainyResolution());

    // CleverTapEventService throws above 1000, so this is required, not an optimisation.
    ArgumentCaptor<List<CleverTapEvent>> events = ArgumentCaptor.forClass(List.class);
    verify(cleverTapEventService, org.mockito.Mockito.times(2)).sendUserEvents(events.capture());
    assertThat(events.getAllValues().get(0)).hasSize(1000);
    assertThat(events.getAllValues().get(1)).hasSize(1);
    assertThat(result.getSent()).isEqualTo(1001);
  }

  @Test
  @DisplayName("a failed chunk does not cost the other chunks their push")
  void failedChunkIsIsolated() {
    List<RainDelayTargetProjection> rows =
        IntStream.range(0, 1001)
            .mapToObj(i -> target(200L + i, 4, 7700L + i))
            .collect(Collectors.toList());
    when(reader.findNotificationTargets(anyList(), any(), any())).thenReturn(rows);
    when(cleverTapEventService.sendUserEvents(anyList()))
        .thenThrow(new RuntimeException("clevertap 503"))
        .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    RainDelayNotifyResult result = service.notifyRainyHubs(rainyResolution());

    // Not rethrown: the activity runs with maximumAttempts(1), so failing here would only hide the
    // chunk that did land.
    assertThat(result.getFailed()).isEqualTo(1000);
    assertThat(result.getSent()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- fixtures

  private static Map<String, SettingsForEnalbeDisable> settings(String rainHubValue) {
    return Map.of("rainHub", setting("rainHub", rainHubValue));
  }

  private static SettingsForEnalbeDisable setting(String key, String value) {
    SettingsForEnalbeDisable s = new SettingsForEnalbeDisable();
    s.setKey(key);
    s.setValue(value);
    return s;
  }

  private static RainyHubResolution rainyResolution() {
    return new RainyHubResolution(
        1,
        1,
        0,
        List.of(4),
        "2026-09-22 14:00:00",
        "2026-09-22 15:00:00",
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  private static RainyHubResolution dryResolution() {
    return new RainyHubResolution(
        1,
        1,
        0,
        List.of(),
        "2026-09-22 14:00:00",
        "2026-09-22 15:00:00",
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  // Plain stubs, not mocks: a mock inside an outer when(...) is nested stubbing.
  private static RainHubCandidateProjection candidate(
      int hubId, String name, String lat, String lng, long bookings) {
    return new RainHubCandidateProjection() {
      @Override
      public Integer getHubId() {
        return hubId;
      }

      @Override
      public String getHubName() {
        return name;
      }

      @Override
      public String getLat() {
        return lat;
      }

      @Override
      public String getLng() {
        return lng;
      }

      @Override
      public Long getBookingCount() {
        return bookings;
      }
    };
  }

  private static RainDelayTargetProjection target(long orderId, int hubId, long userId) {
    return new RainDelayTargetProjection() {
      @Override
      public Long getOrderId() {
        return orderId;
      }

      @Override
      public Integer getHubId() {
        return hubId;
      }

      @Override
      public Long getUserId() {
        return userId;
      }
    };
  }
}
