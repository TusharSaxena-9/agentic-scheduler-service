package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Counts for one chunk, small enough to carry in workflow history (CLAUDE.md rule 9). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EliteMismatchSyncChunkResult {

  /** Invoice and payment both sent. */
  private int succeeded;

  /** Failed anywhere in BusinessPartner / Invoice / IncomingPayment. */
  private int failed;

  /** Already had a {@code tbl_sap_doc_entries} row — posted by an earlier attempt of this chunk. */
  private int alreadySynced;

  /** The subscription row is gone. */
  private int notFound;
}
