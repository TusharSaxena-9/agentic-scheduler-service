package com.yesmadam.schedulerservice.jobs.ozuzuorder.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One sync activity's outcome: how many of the requested order codes were synced, and which ones
 * failed. Bounded by construction — never larger than the {@code orderCodes} list the activity was
 * called with (rule 9).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OzuzuOrderSyncResult {

  private int syncedCount;
  private List<String> failedOrderCodes;
}
