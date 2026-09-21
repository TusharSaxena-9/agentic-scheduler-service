package com.yesmadam.schedulerservice.common.clevertap.exception;

/**
 * Raised when a CleverTap upload fails.
 *
 * <p>Its own package rather than sitting beside {@code CleverTapEventService}: the exception is the
 * part of this integration that callers outside {@code common.clevertap} actually depend on — an
 * activity catches it to decide retryability — so it is worth being addressable without importing
 * the service package.
 *
 * <p>Carries the HTTP status where there was one, so an activity can classify a 4xx as permanent —
 * a bad account id or a malformed payload will not succeed on a second attempt, and spending three
 * attempts and their backoff on it only delays the failure (CLAUDE.md rule 4). {@link #isPermanent}
 * is that decision in one place rather than repeated at each call site.
 */
public class CleverTapException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** HTTP status of the failed response, or {@code 0} when the call never produced one. */
  private final int statusCode;

  public CleverTapException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  public CleverTapException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
  }

  public int getStatusCode() {
    return statusCode;
  }

  /**
   * True for a 4xx — a credential, account or payload problem that a retry cannot fix.
   *
   * <p>A transport failure (status {@code 0}) is deliberately <em>not</em> permanent: a connection
   * reset or a read timeout is exactly the case retrying exists for.
   */
  public boolean isPermanent() {
    return statusCode >= 400 && statusCode < 500;
  }
}
