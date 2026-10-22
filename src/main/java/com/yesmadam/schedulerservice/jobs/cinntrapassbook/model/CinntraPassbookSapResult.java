package com.yesmadam.schedulerservice.jobs.cinntrapassbook.model;

import lombok.Data;

/**
 * Outcome of one SAP POST. Mirrors tracking's {@code req.SapRequestResult}. Never leaves the
 * activity.
 */
@Data
public class CinntraPassbookSapResult {

  private boolean success;

  private int responseCode;

  private String errorMessage;

  private String cardCode;

  private String docEntry;

  private String jdtNum;
}
