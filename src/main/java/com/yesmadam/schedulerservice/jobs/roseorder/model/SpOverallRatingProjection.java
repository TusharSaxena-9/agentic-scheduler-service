package com.yesmadam.schedulerservice.jobs.roseorder.model;

public interface SpOverallRatingProjection {
  Integer getSpId();

  Double getAvgRating();

  Long getRatedInWindow();
}
