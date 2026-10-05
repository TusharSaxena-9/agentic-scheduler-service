package com.yesmadam.schedulerservice.jobs.ozuzuorder.activity;

import java.util.List;

import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderCodesBatch;
import com.yesmadam.schedulerservice.jobs.ozuzuorder.model.OzuzuOrderSyncResult;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activities for the Ozuzu order sync.
 *
 * <p>Search, create and update are three separate activities so each can carry its own timeout
 * and retry profile (rule 4): the search is a handful of fixed HTTP calls, while create/update
 * activities' durations scale with how many order codes the window contains and are the ones that
 * heartbeat (rule 10). Create and update are kept as separate activities — not one combined sync
 * — because they mirror two independent phases in the messagingapis original: a code only ever
 * creates via one, only ever updates via the other (see {@code OzuzuOrderSyncServiceImpl}).
 */
@ActivityInterface
public interface OzuzuOrderSyncActivities {

  /**
   * Searches the trailing window for order codes.
   *
   * @return the window audited and the codes found under each date-type filter
   */
  @ActivityMethod
  OzuzuOrderCodesBatch fetchPendingOrderCodes();

  /**
   * Creates a row for each code not already in {@code tbl_ozuzu_order}. A code failing does not
   * fail the activity — it is reported in the result.
   *
   * @param orderCodes codes from {@link OzuzuOrderCodesBatch#getCreatedSearchCodes()}
   */
  @ActivityMethod
  OzuzuOrderSyncResult createOrders(List<String> orderCodes);

  /**
   * Refreshes the row for each code already in {@code tbl_ozuzu_order}; a code with no existing
   * row is skipped and never created.
   *
   * @param orderCodes codes from {@link OzuzuOrderCodesBatch#getUpdatedSearchCodes()}
   */
  @ActivityMethod
  OzuzuOrderSyncResult updateOrders(List<String> orderCodes);
}
