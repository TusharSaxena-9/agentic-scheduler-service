package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import lombok.Data;

/** Mirrors tracking's {@code SapRequestResult} — the outcome of a single SAP POST, scoped to one call. */
@Data
public class EliteMismatchSyncSapRequestResult {

  private boolean success;

  private int responseCode;

  private String errorMessage;

  private String cardCode;

  private String docEntry;

  private String jdtNum;
}
