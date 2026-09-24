package com.yesmadam.schedulerservice.jobs.ozuzuorder.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrder;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderCodesBatch;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderSyncResult;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceOrderDetail;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceSearchOrderRequest;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.unicommerce.UnicommerceSearchOrderResult;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

/**
 * Syncs Ozuzu (Shopify, via Unicommerce) sale orders created or updated in the trailing window.
 *
 * <p>The window is computed here — inside the service the fetch activity delegates to — rather
 * than in workflow code, per rule 1: a window computed in the workflow slides forward on replay,
 * so a replayed run would silently report a different window than the one it actually queried.
 *
 * <p><b>Deliberately mirrors the messagingapis original's two independent phases</b> rather than
 * collapsing them into one upsert. {@code processCreateOrders} there batch-checked which searched
 * codes already existed and skipped them with no detail fetch at all; {@code processUpdateOrders}
 * batch-checked the same way and skipped — permanently — any code with no existing row. {@link
 * #createOrders} and {@link #updateOrders} reproduce exactly that: a code only ever creates via
 * the create phase, only ever updates via the update phase, and a code the update phase doesn't
 * recognize is dropped rather than created.
 */
@Slf4j
@Service
public class OzuzuOrderSyncServiceImpl implements OzuzuOrderSyncService {

  private final OzuzuUnicommerceClient unicommerceClient;
  private final OzuzuOrderWriter orderWriter;

  @Value("${ozuzuorder.lookback-minutes:20}")
  private int lookbackMinutes;

  @Value("${ozuzuorder.unicommerce.channel}")
  private String channel;

  @Value("#{'${ozuzuorder.unicommerce.facility-codes}'.split(',')}")
  private List<String> facilityCodes;

  public OzuzuOrderSyncServiceImpl(OzuzuUnicommerceClient unicommerceClient, OzuzuOrderWriter orderWriter) {
    this.unicommerceClient = unicommerceClient;
    this.orderWriter = orderWriter;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The window ends at the current instant, truncated to whole seconds — same convention as
   * {@code PassbookMismatchServiceImpl} — so the bounds log and compare cleanly. Both the {@code
   * CREATED} and {@code UPDATED} searches run against this one shared window (the original called
   * {@code buildRequest} twice, once per phase, so its two windows drifted apart by however long
   * the create phase took — an artifact of the two-call structure, not a rule this job depends on).
   *
   * <p>A late-firing catch-up run does not recover a bigger gap: the lookback is fixed at {@code
   * lookbackMinutes} regardless of how late the run starts, so this only ever looks at "the last
   * {@code lookbackMinutes} minutes from now." That is reflected in {@code schedules.yml}'s
   * catchup-window for this job rather than fixed here.
   */
  @Override
  public OzuzuOrderCodesBatch fetchPendingOrderCodes() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Timestamp windowEnd = Timestamp.from(now);
    Timestamp windowStart = Timestamp.from(now.minus(lookbackMinutes, ChronoUnit.MINUTES));

    String accessToken = unicommerceClient.fetchAccessToken();

    QueryTimer timer = new QueryTimer();
    List<UnicommerceSearchOrderResult> createdOrders =
        timer.time(
            "searchCreatedOrders",
            () ->
                unicommerceClient.searchOrders(
                    accessToken, buildSearchRequest(windowStart, windowEnd, "CREATED")));
    List<UnicommerceSearchOrderResult> updatedOrders =
        timer.time(
            "searchUpdatedOrders",
            () ->
                unicommerceClient.searchOrders(
                    accessToken, buildSearchRequest(windowStart, windowEnd, "UPDATED")));

    List<String> createdSearchCodes = codesOf(createdOrders);
    List<String> updatedSearchCodes = codesOf(updatedOrders);

    log.info(
        "event=ozuzu_order_codes_fetched window_start={} window_end={} created_codes={} "
            + "updated_codes={} durations_ms={} row_counts={}",
        windowStart,
        windowEnd,
        createdSearchCodes.size(),
        updatedSearchCodes.size(),
        timer.getDurationsMs(),
        timer.getRowCounts());

    return new OzuzuOrderCodesBatch(
        windowStart,
        windowEnd,
        createdSearchCodes,
        updatedSearchCodes,
        timer.getDurationsMs(),
        timer.getRowCounts());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Existence is batch-checked once up front — {@code OzuzuOrderWriter#existingCodes} — exactly
   * matching the original's one {@code findAllByPropertyIn} call per phase rather than one query
   * per code. A code already present is skipped before any Unicommerce detail call, not just
   * before the write.
   */
  @Override
  public OzuzuOrderSyncResult createOrders(List<String> orderCodes, IntConsumer onOrderProcessed) {
    if (orderCodes == null || orderCodes.isEmpty()) {
      return new OzuzuOrderSyncResult(0, List.of());
    }

    Set<String> existingCodes = orderWriter.existingCodes(orderCodes);
    String accessToken = unicommerceClient.fetchAccessToken();

    int syncedCount = 0;
    List<String> failedOrderCodes = new ArrayList<>();
    int processed = 0;

    for (String orderCode : orderCodes) {
      try {
        if (existingCodes.contains(orderCode)) {
          continue;
        }
        UnicommerceOrderDetail detail = unicommerceClient.fetchOrderDetail(accessToken, orderCode);
        if (detail == null) {
          failedOrderCodes.add(orderCode);
        } else {
          orderWriter.insertNew(detail);
          syncedCount++;
        }
      } catch (RuntimeException e) {
        log.error("event=ozuzu_order_create_failed order_code={}", orderCode, e);
        failedOrderCodes.add(orderCode);
      } finally {
        processed++;
        onOrderProcessed.accept(processed);
      }
    }

    log.info(
        "event=ozuzu_order_create_completed requested={} created={} failed={}",
        orderCodes.size(),
        syncedCount,
        failedOrderCodes.size());

    return new OzuzuOrderSyncResult(syncedCount, failedOrderCodes);
  }

  /**
   * {@inheritDoc}
   *
   * <p>A code with no existing row is skipped and never created — matching the original's {@code
   * if (ObjectUtils.isEmpty(order)) continue;} in {@code processUpdateOrders}.
   */
  @Override
  public OzuzuOrderSyncResult updateOrders(List<String> orderCodes, IntConsumer onOrderProcessed) {
    if (orderCodes == null || orderCodes.isEmpty()) {
      return new OzuzuOrderSyncResult(0, List.of());
    }

    Map<String, OzuzuOrder> existingOrdersByCode = orderWriter.existingOrdersByCode(orderCodes);
    String accessToken = unicommerceClient.fetchAccessToken();

    int syncedCount = 0;
    List<String> failedOrderCodes = new ArrayList<>();
    int processed = 0;

    for (String orderCode : orderCodes) {
      try {
        OzuzuOrder existing = existingOrdersByCode.get(orderCode);
        if (existing == null) {
          continue;
        }
        UnicommerceOrderDetail detail = unicommerceClient.fetchOrderDetail(accessToken, orderCode);
        if (detail == null) {
          failedOrderCodes.add(orderCode);
        } else {
          orderWriter.updateExisting(existing, detail);
          syncedCount++;
        }
      } catch (RuntimeException e) {
        log.error("event=ozuzu_order_update_failed order_code={}", orderCode, e);
        failedOrderCodes.add(orderCode);
      } finally {
        processed++;
        onOrderProcessed.accept(processed);
      }
    }

    log.info(
        "event=ozuzu_order_update_completed requested={} updated={} failed={}",
        orderCodes.size(),
        syncedCount,
        failedOrderCodes.size());

    return new OzuzuOrderSyncResult(syncedCount, failedOrderCodes);
  }

  private UnicommerceSearchOrderRequest buildSearchRequest(
      Timestamp windowStart, Timestamp windowEnd, String dateType) {
    return new UnicommerceSearchOrderRequest(
        String.valueOf(windowStart.toInstant().toEpochMilli()),
        String.valueOf(windowEnd.toInstant().toEpochMilli()),
        dateType,
        channel,
        facilityCodes);
  }

  private List<String> codesOf(List<UnicommerceSearchOrderResult> results) {
    List<String> codes = new ArrayList<>(results.size());
    for (UnicommerceSearchOrderResult result : results) {
      if (result.getCode() != null && !result.getCode().isBlank()) {
        codes.add(result.getCode());
      }
    }
    return codes;
  }
}
