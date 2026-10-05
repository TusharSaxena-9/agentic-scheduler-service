package com.yesmadam.schedulerservice.jobs.ozuzuorder.service;

import java.util.List;
import java.util.function.IntConsumer;

import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderCodesBatch;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderSyncResult;

/**
 * Business logic for the Ozuzu (Shopify, via Unicommerce) order sync. No {@code io.temporal}
 * import here by design (rule 12 / migration checklist item 5) — plain JUnit exercises every
 * branch, including the empty-result and partial-failure paths, with no Temporal test
 * environment.
 *
 * <p>Mirrors the messagingapis original's two-phase shape: a code found by the {@code CREATED}
 * search only ever creates a row (skipped if one already exists); a code found by the {@code
 * UPDATED} search only ever refreshes a row that already exists (skipped, never created,
 * otherwise).
 */
public interface OzuzuOrderSyncService {

  /**
   * Searches the trailing window for order codes Unicommerce reports as created or updated.
   *
   * @return the window audited and the codes found under each date-type filter
   */
  OzuzuOrderCodesBatch fetchPendingOrderCodes();

  /**
   * Creates a row for each code not already in {@code tbl_ozuzu_order}; a code already present is
   * skipped before any detail fetch, exactly as {@code OzuzuServiceImpl.processCreateOrders} did.
   *
   * @param orderCodes codes from {@link OzuzuOrderCodesBatch#getCreatedSearchCodes()}
   * @param onOrderProcessed invoked after each code (created, skipped or failed) with the running
   *     count; the activity wires this to a Temporal heartbeat (rule 10) without pulling {@code
   *     io.temporal} into this class — pass a no-op for a plain-JUnit test
   */
  OzuzuOrderSyncResult createOrders(List<String> orderCodes, IntConsumer onOrderProcessed);

  /**
   * Refreshes the row for each code already in {@code tbl_ozuzu_order}; a code with no existing
   * row is skipped and never created, exactly as {@code OzuzuServiceImpl.processUpdateOrders} did.
   *
   * @param orderCodes codes from {@link OzuzuOrderCodesBatch#getUpdatedSearchCodes()}
   * @param onOrderProcessed invoked after each code (updated, skipped or failed) with the running
   *     count
   */
  OzuzuOrderSyncResult updateOrders(List<String> orderCodes, IntConsumer onOrderProcessed);
}
