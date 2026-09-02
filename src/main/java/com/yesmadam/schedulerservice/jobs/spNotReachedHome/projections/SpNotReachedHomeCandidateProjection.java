package com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections;

import java.sql.Timestamp;

/**
 * One SP who finished their last job of the day after 20:00 and is currently further than the
 * configured threshold from their registered home address.
 *
 * <p>Column aliases in the query are written in camelCase so they map onto these getters directly,
 * rather than relying on Spring Data's snake_case translation for native-query projections.
 */
public interface SpNotReachedHomeCandidateProjection {

  Integer getSpId();

  String getSpName();

  Long getLastOrderId();

  Timestamp getLastOrderEndTime();

  Double getCurrentLat();

  Double getCurrentLon();

  String getHomeLat();

  String getHomeLon();

  Double getDistanceFromHomeMtr();
}
