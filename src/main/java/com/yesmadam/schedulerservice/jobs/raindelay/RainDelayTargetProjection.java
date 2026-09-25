package com.yesmadam.schedulerservice.jobs.raindelay;

/** One customer to warn. */
public interface RainDelayTargetProjection {

  Long getOrderId();

  Integer getHubId();

  /** {@code tbl_order.user_id} — the CleverTap identity the push is attributed to. */
  Long getUserId();
}
