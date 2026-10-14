package com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.common.clevertap.CleverTapEventProducer;
import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEventPayload;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.model.SpTomorrowJobReminderBatch;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.projections.SpTomorrowJobProjection;
import com.yesmadam.schedulerservice.jobs.sptomorrowjobreminder.repository.SpTomorrowJobReminderRepository;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
public class SpTomorrowJobReminderServiceImpl implements SpTomorrowJobReminderService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SpTomorrowJobReminderRepository repository;
  private final CleverTapEventProducer cleverTapEventProducer;

  @Value("${sptomorrowjobreminder.event-name:SP_Campaign_Push}")
  private String eventName;

  @Value("${sptomorrowjobreminder.push.title}")
  private String pushTitle;

  @Value("${sptomorrowjobreminder.push.body}")
  private String pushBody;

  @Value("${sptomorrowjobreminder.push.deeplink-code}")
  private Integer deeplinkCode;

  public SpTomorrowJobReminderServiceImpl(
      SpTomorrowJobReminderRepository repository, CleverTapEventProducer cleverTapEventProducer) {
    this.repository = repository;
    this.cleverTapEventProducer = cleverTapEventProducer;
  }


  @Override
  @Transactional(readOnly = true)
  public SpTomorrowJobReminderBatch fetchTomorrowReminders() {
    LocalDate tomorrow = LocalDate.now(IST).plusDays(1);
    Date bookingDate = Date.valueOf(tomorrow);
    List<Integer> cancelledStatusCodes =
        new ArrayList<>(JobDistributionConstants.CANCELLED_ORDER_STATUS_CODES);

    QueryTimer timer = new QueryTimer();
    List<SpTomorrowJobProjection> found =
            timer.time(
                    "findSpIdsWithJobOnBookingDate",
                    () -> repository.findSpIdsWithJobOnBookingDate(bookingDate, cancelledStatusCodes));

    List<Integer> spIds =
        found.stream()
            .map(SpTomorrowJobProjection::getSpId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

    log.info(
        "event=sp_tomorrow_job_reminder_scanned job_date={} sp_count={} durations_ms={} "
            + "row_counts={}",
        tomorrow,
        spIds.size(),
        timer.getDurationsMs(),
        timer.getRowCounts());

    return new SpTomorrowJobReminderBatch(
        tomorrow, spIds, timer.getDurationsMs(), timer.getRowCounts());
  }


  @Override
  public void sendReminders(SpTomorrowJobReminderBatch batch) {
    if (batch.getSpIds() == null || batch.getSpIds().isEmpty()) {
      log.info(
          "event=sp_tomorrow_job_reminder_send_skipped reason=no_sps job_date={}",
          batch.getJobDate());
      return;
    }

    Map<String, Object> evtData =
        Map.of(
            "msgTitle", pushTitle,
            "msgBody", pushBody,
            "deeplinkCode", deeplinkCode);
    List<CleverTapEventPayload> events =
        batch.getSpIds().stream()
            .map(spId -> new CleverTapEventPayload(spId, eventName, evtData))
            .collect(Collectors.toList());

    cleverTapEventProducer.publish(events);

    log.info(
        "event=sp_tomorrow_job_reminder_sent job_date={} sp_count={}",
        batch.getJobDate(),
        events.size());
  }
}
