package com.yesmadam.schedulerservice.jobs.billingcheck.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.yesmadam.enumu.OrderStatus;
import com.yesmadam.schedulerservice.common.progress.ProgressReporter;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckChunk;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckOrder;
import com.yesmadam.schedulerservice.jobs.billingcheck.model.BillingCheckSendResult;
import com.yesmadam.schedulerservice.jobs.billingcheck.projections.BillingCheckOrderProjection;
import com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp.BillingCheckSendException;
import com.yesmadam.schedulerservice.jobs.billingcheck.whatsapp.BillingCheckWhatsappService;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

/**
 * Post-service billing check: WhatsApps the customer their final bill and asks whether the
 * beautician did anything not on it.
 *
 * <p>Two steps, deliberately separate so they can have different retry rules. {@link #fetchChunk} is
 * a pure read, safe to retry. {@link #sendChunk} is not — a WhatsApp cannot be taken back — and keeps
 * no sent-marker (a deliberate choice: no table, no flag), so the workflow runs it exactly once.
 * That means a worker killed mid-chunk loses the rest of that chunk rather than risking a duplicate;
 * failed sends are logged with their order id so they can be re-driven by hand if it ever matters.
 */
@Slf4j
@Service
public class BillingCheckService {

  static final int SERVICES_MAX_CHARS = 60;
  private static final String SERVICES_TRUNCATION_SUFFIX = " and more";

  /** Completed, and completed-with-feedback — the two states the PRD counts as a finished booking. */
  private static final List<Integer> ELIGIBLE_STATUS_CODES =
      List.of(OrderStatus.ORDER_COMPLETED.getStatus(), OrderStatus.ORDER_FEEDBACK_DONE.getStatus());

  private final BillingCheckReader reader;
  private final BillingCheckWhatsappService whatsappService;
  private final BillingCheckProperties properties;

  public BillingCheckService(
      BillingCheckReader reader,
      BillingCheckWhatsappService whatsappService,
      BillingCheckProperties properties) {
    this.reader = reader;
    this.whatsappService = whatsappService;
    this.properties = properties;
  }

  public BillingCheckChunk fetchChunk(long nominalFireTimeMillis, long afterOrderId) {
    BillingCheckWindow window = BillingCheckWindow.forNominalFireTime(nominalFireTimeMillis);
    int chunkSize = properties.getChunkSize();

    QueryTimer timer = new QueryTimer();
    List<BillingCheckOrderProjection> rows =
        timer.time(
            "findEligibleOrders",
            () ->
                reader.findEligibleOrders(
                    ELIGIBLE_STATUS_CODES,
                    properties.getCategoryIds(),
                    window,
                    afterOrderId,
                    chunkSize));

    List<BillingCheckOrder> orders = rows.stream().map(this::toOrder).collect(Collectors.toList());
    boolean done = rows.size() < chunkSize;
    long nextCursor = rows.isEmpty() ? afterOrderId : rows.get(rows.size() - 1).getOrderId();

    log.info(
        "event=billing_check_fetched window_start={} window_end={} after_order_id={} fetched={} "
            + "done={} durations_ms={} row_counts={}",
        window.start(),
        window.end(),
        afterOrderId,
        orders.size(),
        done,
        timer.getDurationsMs(),
        timer.getRowCounts());

    return new BillingCheckChunk(
        nextCursor, done, orders, timer.getDurationsMs(), timer.getRowCounts());
  }

  public BillingCheckSendResult sendChunk(BillingCheckChunk chunk, ProgressReporter progress) {
    int sent = 0;
    int failed = 0;
    int processed = 0;
    for (BillingCheckOrder order : chunk.getOrders()) {
      // Outside the try on purpose: heartbeat throws when the activity is cancelled, and that
      // exception must escape (ProgressReporter contract), not be counted as a send failure.
      progress.report(processed++);
      try {
        whatsappService.send(
            String.valueOf(order.getMobile()),
            properties.getTemplateName(),
            order.getBodyParams(),
            order.getOrderId());
        sent++;
      } catch (BillingCheckSendException ex) {
        failed++;
        log.error(
            "event=billing_check_send_failed order_id={} error={}", order.getOrderId(), ex.getMessage());
      }
    }
    log.info("event=billing_check_sent chunk_size={} sent={} failed={}", chunk.getOrders().size(), sent, failed);
    return new BillingCheckSendResult(sent, failed);
  }

  private BillingCheckOrder toOrder(BillingCheckOrderProjection p) {
    return new BillingCheckOrder(p.getOrderId(), p.getMobile(), bodyParams(p));
  }

  /** {{1}} first name, {{2}} category, {{3}} final amount, {{4}} billed services. */
  List<String> bodyParams(BillingCheckOrderProjection order) {
    return List.of(
        firstName(order.getCustomerName()),
        StringUtils.hasText(order.getCategoryName()) ? order.getCategoryName() : "YesMadam",
        String.valueOf(BillingAmountCalculator.finalAmount(order)),
        servicesLine(order.getServiceNames()));
  }

  static String firstName(String name) {
    if (!StringUtils.hasText(name)) {
      return "there";
    }
    return name.trim().split("\\s+")[0];
  }

  /**
   * One line, no newlines, at most {@value #SERVICES_MAX_CHARS} characters of service names followed
   * by "and more" when cut. Cuts at a service boundary where one exists, so a name is never split
   * mid-word.
   */
  static String servicesLine(String serviceNames) {
    if (!StringUtils.hasText(serviceNames)) {
      return "your booked services";
    }
    String oneLine = serviceNames.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
    if (oneLine.length() <= SERVICES_MAX_CHARS) {
      return oneLine;
    }
    String head = oneLine.substring(0, SERVICES_MAX_CHARS);
    int lastSeparator = head.lastIndexOf(',');
    String cut = lastSeparator > 0 ? head.substring(0, lastSeparator) : head.trim();
    return cut + SERVICES_TRUNCATION_SUFFIX;
  }
}
