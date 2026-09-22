package com.yesmadam.schedulerservice.jobs.couponwhatsapp.activity;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.yesmadam.schedulerservice.annotation.HandleExceptions;
import com.yesmadam.schedulerservice.annotation.Loggable;
import com.yesmadam.dto.CouponWhatsappMessage;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.projections.CouponWhatsappCandidateProjection;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.service.CouponWhatsappReminderReader;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.service.CouponWhatsappReminderWriter;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.service.CouponWhatsappSqsProducer;

import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
@ActivityImpl(taskQueues = "${temporal.taskqueue.standard}")
public class CouponWhatsappReminderActivitiesImpl implements CouponWhatsappReminderActivities {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final CouponWhatsappReminderReader reader;
  private final CouponWhatsappReminderWriter writer;
  private final CouponWhatsappSqsProducer sqsProducer;

  @Value("${com.yesmadam.coupon-whatsapp-reminder.queue-url:}")
  private String queueUrl;

  public CouponWhatsappReminderActivitiesImpl(
      CouponWhatsappReminderReader reader,
      CouponWhatsappReminderWriter writer,
      CouponWhatsappSqsProducer sqsProducer) {
    this.reader = reader;
    this.writer = writer;
    this.sqsProducer = sqsProducer;
  }

  @Override
  @Loggable(operation = "enqueue-pending-coupon-whatsapp")
  @HandleExceptions
  public int enqueuePendingCouponWhatsapp() {
    LocalDate yesterday = LocalDate.now(IST).minusDays(1);
    Timestamp windowStart = Timestamp.valueOf(yesterday.atStartOfDay());
    Timestamp windowEnd = Timestamp.valueOf(yesterday.plusDays(1).atStartOfDay());

    List<CouponWhatsappCandidateProjection> candidates = reader.fetchCandidates(windowStart, windowEnd);
    if (candidates.isEmpty()) {
      return 0;
    }

    String resolvedQueueUrl = resolveQueueUrl();
    List<Long> sentClaimIds = new ArrayList<>(candidates.size());
    try {
      for (CouponWhatsappCandidateProjection c : candidates) {
        sqsProducer.sendOne(resolvedQueueUrl, toMessage(c));
        sentClaimIds.add(c.getClaimId());
      }
    } finally {
      // Batch-claim whatever actually made it to SQS — including on a partial failure — so a
      // retry's fetch query (rule: wa_status = PENDING) never sees, and never re-publishes, these
      // again. One UPDATE for the whole batch rather than one per claim.
      writer.markDelivered(sentClaimIds);
    }
    return sentClaimIds.size();
  }

  private CouponWhatsappMessage toMessage(CouponWhatsappCandidateProjection c) {
    return new CouponWhatsappMessage(
        c.getClaimId(),
        c.getOrderId(),
        c.getUserId(),
        c.getMobile(),
        c.getCustomerName(),
        c.getBrandName(),
        c.getCodeSnapshot(),
        c.getExpiryAt() != null ? c.getExpiryAt().getTime() : null);
  }

  private String resolveQueueUrl() {
    if (!StringUtils.hasText(queueUrl)) {
      throw new IllegalStateException(
          "No SQS queue URL configured for coupon-WhatsApp reminders "
              + "(com.yesmadam.coupon-whatsapp-reminder.queue-url) — demo placeholder only, "
              + "set the real queue URL before enabling this job");
    }
    return queueUrl;
  }
}
