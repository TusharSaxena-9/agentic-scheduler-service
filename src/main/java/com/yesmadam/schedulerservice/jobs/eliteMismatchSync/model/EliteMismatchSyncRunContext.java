package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything one run needs, resolved once before chunking so every chunk prices subscriptions
 * exactly as tracking's single loop did.
 *
 * <p>Tracking resolves the order elite-fee map and the {@code YmElite} plan amount <i>once</i>,
 * from the first subscription of the whole batch, and reuses them for every subscription. Chunking
 * must not change which subscription that is, so both are computed here from the full list and
 * handed to each chunk unchanged.
 *
 * <p>Bounded payload (CLAUDE.md rule 9): the ids are one IST day's Elite subscriptions, and the fee
 * map holds one customer's orders.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EliteMismatchSyncRunContext {

  /** Subscriptions to invoice, in tracking's order ({@code createdAt DESC}), missing rows dropped. */
  private List<Integer> subscriptionIds;

  /** Subscription id to elite fee, from the first subscription's customer's orders (newest wins). */
  private Map<Integer, Integer> eliteFeeBySubscriptionId;

  /** The first subscription's {@code YmElite} plan amount; {@code null} when the plan is missing. */
  private Integer fallbackPlanAmount;
}
