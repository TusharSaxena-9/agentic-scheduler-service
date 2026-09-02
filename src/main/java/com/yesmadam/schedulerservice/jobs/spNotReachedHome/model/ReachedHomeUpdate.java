package com.yesmadam.schedulerservice.jobs.spNotReachedHome.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/** One tracker row that has been confirmed home, with the position that confirmed it. */
@Data
@AllArgsConstructor
public class ReachedHomeUpdate {

  private Long trackerRowId;
  private Double currentLat;
  private Double currentLon;
}
