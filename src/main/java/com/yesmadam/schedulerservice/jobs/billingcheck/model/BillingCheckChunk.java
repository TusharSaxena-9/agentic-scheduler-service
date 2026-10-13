package com.yesmadam.schedulerservice.jobs.billingcheck.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One keyset page of eligible orders. Bounded by the chunk size (CLAUDE.md rule 9) — the workflow
 * holds this one page and a cursor, never the day's full list.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingCheckChunk implements Serializable {

  /** Highest {@code order_id} in this page; the next fetch resumes strictly after it. */
  private long nextCursor;

  /** True once a page came back shorter than the chunk size — nothing left in the window. */
  private boolean done;

  private List<BillingCheckOrder> orders = new ArrayList<>();
  private Map<String, Long> queryDurationsMs = new LinkedHashMap<>();
  private Map<String, Integer> queryRowCounts = new LinkedHashMap<>();
}
