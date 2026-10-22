package com.yesmadam.schedulerservice.jobs.cinntrapassbook.service;

/**
 * SAP rejected the session, and refreshing it once did not help — or there was no usable session
 * row to begin with.
 *
 * <p>Deliberately not handled per entry. Every entry after this one would fail the same way, so the
 * posters rethrow it, the chunk activity fails, and Temporal retries the chunk; entries already
 * posted are skipped on the retry. Catching it per entry would instead mark every remaining entry
 * failed and write one {@code tbl_sap_error_log} row each, for a problem that was never theirs.
 */
public class CinntraPassbookSapSessionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with a message naming what was rejected. */
  public CinntraPassbookSapSessionException(String message) {
    super(message);
  }
}
