package com.yesmadam.schedulerservice.jobs.raindelay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Counts from one send activity — counts, not the recipient list. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RainDelayNotifyResult {

  /** Customers in this run's slot at a rainy hub. */
  private int targets;

  private int sent;

  /** Rejected sends. Does not fail the activity — a retry would re-send to everyone else. */
  private int failed;
}
