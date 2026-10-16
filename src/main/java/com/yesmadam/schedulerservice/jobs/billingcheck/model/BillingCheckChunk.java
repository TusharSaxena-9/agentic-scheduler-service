package com.yesmadam.schedulerservice.jobs.billingcheck.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One keyset page of eligible orders, bounded by the chunk size (rule 9). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingCheckChunk implements Serializable {

  /** Highest {@code order_id} in this page; the next fetch starts after it. */
  private long nextCursor;

  /** True once a page came back shorter than the chunk size. */
  private boolean done;

  private List<BillingCheckOrder> orders = new ArrayList<>();
  private Map<String, Long> queryDurationsMs = new LinkedHashMap<>();
  private Map<String, Integer> queryRowCounts = new LinkedHashMap<>();
}
