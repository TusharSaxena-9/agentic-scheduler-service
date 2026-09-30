package com.yesmadam.schedulerservice.jobs.raindelay;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEvent;
import com.yesmadam.schedulerservice.common.clevertap.service.CleverTapEventService;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;
import com.yesmadam.schedulerservice.utils.QueryTimer;
import com.yesmadam.schedulerservice.utils.SettingConfigUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * The job's business logic. Imports nothing from {@code io.temporal}, so it is testable with plain
 * JUnit and the activity stays a thin adapter.
 *
 * <p>The window is computed here, inside the activity's call path, and returned with the data — in
 * workflow code it would slide forward on replay.
 */
@Slf4j
@Service
public class RainDelayService {

  /**
   * Gap between a run's scheduled time and the start of the slot it covers. A 10:00 run handles
   * [10:30, 11:00), giving every customer at least 30 minutes' notice.
   */
  static final int WINDOW_OFFSET_MINUTES = 30;

  /**
   * Length of the slot each run covers. <b>Must equal the cron period in schedules.yml</b> — that
   * equality is what makes consecutive windows tile with no gap and no overlap. Change one and you
   * must change the other, or orders fall between runs and are never notified.
   */
  static final int WINDOW_LENGTH_MINUTES = 300;

  /**
   * Fallback rain-risk bar in mm, applied when the {@code RAIN_PRECIP_MM_THRESHOLD} settings row is
   * missing, blank or unparseable.
   */
  static final BigDecimal DEFAULT_PRECIP_THRESHOLD_MM = BigDecimal.valueOf(0.0);

  /** CleverTap's {@code /1/upload} ceiling; {@code CleverTapEventService} throws above it. */
  private static final int CLEVERTAP_EVENTS_PER_UPLOAD = 1000;

  /** App route for live booking status; must match the {@code deeplinkCode} in the deeplink prefix. */
  private static final String DEEPLINK_CODE = "27";

  /** Explicit zone: the pod's TZ lives in the k8s manifest and is absent on a dev machine. */
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final DateTimeFormatter WINDOW_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);

  @Value("${raindelay.clevertap.event-name}")
  private String eventName;

  @Value("${raindelay.clevertap.title}")
  private String pushTitle;

  @Value("${raindelay.clevertap.body}")
  private String pushBody;

  /** Deep link base; the order id is appended. {@code https://www.yesmadam.com/?deeplinkCode=27&orderId=}. */
  @Value("${raindelay.deeplink-prefix}")
  private String deeplinkPrefix;

  private final SettingService settingService;
  private final RainDelayReader reader;
  private final WeatherClient weatherClient;
  private final CleverTapEventService cleverTapEventService;

  public RainDelayService(
      SettingService settingService,
      RainDelayReader reader,
      WeatherClient weatherClient,
      CleverTapEventService cleverTapEventService) {
    this.settingService = settingService;
    this.reader = reader;
    this.weatherClient = weatherClient;
    this.cleverTapEventService = cleverTapEventService;
  }

  /**
   * Resolves which configured hubs are rainy and have a booking in this run's slot.
   *
   * <p><b>The window is derived from the nominal scheduled time, never from wall-clock now.</b>
   * Windows are non-overlapping tiles — [10:30, 11:00), [11:00, 11:30) — so nothing else dedupes
   * them. A window built from {@code now()} would slide forward whenever a run started late,
   * leaving a gap between it and the previous tile that no run ever covers, and those orders would
   * silently never be notified (CLAUDE.md rule 1).
   *
   * <p>Short-circuits before touching {@code tbl_order} when nothing is configured. The weather API
   * is called once per candidate hub — never per configured hub, never per order — so a quiet slot
   * costs one query and zero HTTP calls.
   *
   * @param nominalFireTimeMillis the run's scheduled time, from {@code Workflow.currentTimeMillis()}
   */
  public RainyHubResolution resolveRainyHubs(long nominalFireTimeMillis) {

    // Both settings keys in one query — this is the only settings read in the run.
    Map<String, SettingsForEnalbeDisable> cfg =
        settingService.findSettingWithKeys(List.of("rainHub", "RAIN_PRECIP_MM_THRESHOLD"));
    // Tolerates spaces and a trailing comma; drops a non-numeric token rather than the whole row.
    List<Integer> configuredHubIds = SettingConfigUtil.intListConfig(cfg, "rainHub");
    double precipThresholdMm = precipThreshold(cfg);

    LocalDateTime scheduled =
        Instant.ofEpochMilli(nominalFireTimeMillis).atZone(IST).toLocalDateTime();
    LocalDateTime windowStart = scheduled.plusMinutes(WINDOW_OFFSET_MINUTES);
    LocalDateTime windowEnd =
        scheduled.plusMinutes(WINDOW_OFFSET_MINUTES + WINDOW_LENGTH_MINUTES);

    String formattedStart = windowStart.format(WINDOW_FORMAT);
    String formattedEnd = windowEnd.format(WINDOW_FORMAT);

    if (configuredHubIds.isEmpty()) {
      log.warn(
          "event=rain_delay_not_configured key=rainHub window_start={} window_end={}",
          formattedStart,
          formattedEnd);
      return RainyHubResolution.empty(0, formattedStart, formattedEnd);
    }

    QueryTimer timer = new QueryTimer();
    List<RainHubCandidateProjection> candidates =
        timer.time(
            "findCandidateHubs",
            (QueryTimer.ListSupplier<RainHubCandidateProjection>)
                () -> reader.findCandidateHubs(configuredHubIds, windowStart, windowEnd));

    if (candidates.isEmpty()) {
      log.info(
          "event=rain_delay_no_candidate_hubs configured={} window_start={} window_end={}",
          configuredHubIds.size(),
          formattedStart,
          formattedEnd);
      RainyHubResolution empty =
          RainyHubResolution.empty(configuredHubIds.size(), formattedStart, formattedEnd);
      empty.setQueryDurationsMs(timer.getDurationsMs());
      empty.setQueryRowCounts(timer.getRowCounts());
      return empty;
    }

    List<Integer> rainyHubIds = new ArrayList<>();
    int unknownHubCount = 0;

    for (RainHubCandidateProjection candidate : candidates) {
      HubWeather weather =
          weatherClient.weatherAt(
              candidate.getHubId(),
              candidate.getLat(),
              candidate.getLng(),
              precipThresholdMm);
      if (weather == HubWeather.RAINY) {
        rainyHubIds.add(candidate.getHubId());
      } else if (weather == HubWeather.UNKNOWN) {
        unknownHubCount++;
      }
    }

    RainyHubResolution resolution =
        new RainyHubResolution(
            configuredHubIds.size(),
            candidates.size(),
            unknownHubCount,
            rainyHubIds,
            formattedStart,
            formattedEnd,
            timer.getDurationsMs(),
            timer.getRowCounts());

    log.info(
        "event=rain_delay_hubs_resolved configured={} candidates={} rainy={} unknown={} "
            + "threshold_mm={} window_start={} window_end={} query_ms={}",
        configuredHubIds.size(),
        candidates.size(),
        rainyHubIds.size(),
        unknownHubCount,
        precipThresholdMm,
        formattedStart,
        formattedEnd,
        timer.getDurationsMs());

    return resolution;
  }

  /**
   * Notifies the customers at the rainy hubs.
   *
   * <p>Reuses the resolved window rather than recomputing it, so the notified set is the tile the
   * weather verdict was actually made against.
   *
   * <p><b>There is no per-order sent marker.</b> Cross-run duplicates are prevented by the windows
   * being non-overlapping tiles rather than by database tracking, so an order belongs to exactly
   * one run and no dedupe table is needed. The consequence is that a <i>retry</i> of this activity
   * would re-send to everyone the first attempt already reached, which is why the notify stub is
   * pinned to {@code maximumAttempts(1)} — see {@code RainDelayWorkflowImpl}. Same tradeoff, and
   * the same reasoning, as {@code FeedbackReminderWorkflowImpl}.
   *
   * <p><b>Do not put {@code @Transactional} on this method.</b> The loop is HTTP; a transaction
   * here would pin a database connection for the length of the provider's timeout (rule 13).
   */
  public RainDelayNotifyResult notifyRainyHubs(RainyHubResolution resolution) {
    if (resolution == null
        || resolution.getRainyHubIds() == null
        || resolution.getRainyHubIds().isEmpty()) {
      return new RainDelayNotifyResult(0, 0, 0);
    }

    LocalDateTime windowStart = LocalDateTime.parse(resolution.getWindowStart(), WINDOW_FORMAT);
    LocalDateTime windowEnd = LocalDateTime.parse(resolution.getWindowEnd(), WINDOW_FORMAT);

    QueryTimer timer = new QueryTimer();
    List<RainDelayTargetProjection> rows =
        timer.time(
            "findNotificationTargets",
            (QueryTimer.ListSupplier<RainDelayTargetProjection>)
                () ->
                    reader.findNotificationTargets(
                        resolution.getRainyHubIds(), windowStart, windowEnd));

    if (rows.isEmpty()) {
      log.info(
          "event=rain_delay_no_targets rainy_hubs={} query_ms={}",
          resolution.getRainyHubIds().size(),
          timer.getDurationsMs());
      return new RainDelayNotifyResult(0, 0, 0);
    }

    List<RainDelayTarget> targets = new ArrayList<>(rows.size());
    for (RainDelayTargetProjection row : rows) {
      targets.add(new RainDelayTarget(row.getOrderId(), row.getHubId(), row.getUserId()));
    }

    // One HTTP call per chunk, not one per customer. CleverTap caps an upload at 1000 events and
    // CleverTapEventService throws above that, so chunking here is required, not an optimisation.
    int sent = 0;
    int failed = 0;
    for (int from = 0; from < targets.size(); from += CLEVERTAP_EVENTS_PER_UPLOAD) {
      List<RainDelayTarget> chunk =
          targets.subList(
              from, Math.min(from + CLEVERTAP_EVENTS_PER_UPLOAD, targets.size()));
      List<CleverTapEvent> events = new ArrayList<>(chunk.size());
      for (RainDelayTarget target : chunk) {
        events.add(
            CleverTapEvent.of("4621955", eventName, evtData(target)));
      }
      try {
        sent += cleverTapEventService.sendUserEvents(events);
      } catch (RuntimeException e) {
        failed += chunk.size();
        log.error(
            "event=rain_delay_push_chunk_failed size={} error={}", chunk.size(), e.getMessage());
      }
    }

    return new RainDelayNotifyResult(targets.size(), sent, failed);
  }

  /**
   * The push payload.
   *
   * <p><b>These key names are a contract with a CleverTap campaign that lives outside this repo.</b>
   * Rename one and the upload is still accepted with a 200, and the push then renders blank or the
   * tap goes nowhere. {@code RainDelayServiceTest} pins them for that reason.
   */
  private Map<String, Object> evtData(RainDelayTarget target) {
    Map<String, Object> evtData = new LinkedHashMap<>();
    evtData.put("orderId", target.getOrderId());
    evtData.put("deeplinkCode", DEEPLINK_CODE);
    evtData.put("msgTitle", pushTitle);
    evtData.put("msgBody", pushBody);
    // Lands the customer on live booking status rather than the app home screen.
    evtData.put("deeplink", deeplinkPrefix + target.getOrderId());
    return evtData;
  }

  /**
   * Rain-risk bar from {@code tbl_settings}, falling back on a missing, unparseable or negative
   * value. Zero is allowed on purpose: {@code precip_mm >= 0} is always true, so every candidate
   * hub counts as rainy — useful for testing on a dry day, but it messages every customer at every
   * configured hub, so never leave it at 0 in prod.
   */
  private static double precipThreshold(Map<String, SettingsForEnalbeDisable> cfg) {
    BigDecimal configured =
        SettingConfigUtil.decimalConfig(
            cfg, "RAIN_PRECIP_MM_THRESHOLD", DEFAULT_PRECIP_THRESHOLD_MM);
    if (configured.signum() >= 0) {
      return configured.doubleValue();
    }
    log.error(
        "event=rain_delay_config_invalid key=RAIN_PRECIP_MM_THRESHOLD value={} using_default={}",
        configured,
        DEFAULT_PRECIP_THRESHOLD_MM);
    return DEFAULT_PRECIP_THRESHOLD_MM.doubleValue();
  }
}
