package com.yesmadam.schedulerservice.jobs.raindelay;

/**
 * A hub with bookings in the lookahead window, plus the coordinates the weather lookup needs.
 *
 * <p>Its own file, not nested in the repository, so {@code ProjectionTypeAgreementTest} — which
 * scans {@code *Projection.java} — keeps guarding it.
 */
public interface RainHubCandidateProjection {

  Integer getHubId();

  String getHubName();

  /** {@code tbl_hub.lat} — VARCHAR, so may be null, blank or unparseable. */
  String getLat();

  /** {@code tbl_hub.lng} — VARCHAR, so may be null, blank or unparseable. */
  String getLng();

  Long getBookingCount();
}
