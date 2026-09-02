package com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections;

/**
 * A {@code tbl_sp_not_reached_home} row still marked {@code reached_home = 0} today, joined to the
 * SP's registered home coordinates so the distance can be recomputed in Java.
 */
public interface PendingNotReachedHomeProjection {

  Long getId();

  Integer getSpId();

  String getHomeLat();

  String getHomeLon();
}
