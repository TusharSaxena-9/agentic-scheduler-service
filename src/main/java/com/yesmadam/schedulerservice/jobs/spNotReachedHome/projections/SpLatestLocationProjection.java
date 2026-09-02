package com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections;

/** An SP's most recent ping in {@code tbl_sp_location_lasthour} within the lookback window. */
public interface SpLatestLocationProjection {

  Integer getSpId();

  Double getLat();

  Double getLon();
}
