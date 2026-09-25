package com.yesmadam.schedulerservice.jobs.raindelay;

/**
 * One hub's weather verdict. Three states, not a boolean: a failed lookup must not read as a clear
 * day, or an outage looks identical to dry weather.
 */
public enum HubWeather {
  RAINY,
  CLEAR,
  /** Lookup failed. Never notifies, and counted separately from CLEAR so an outage is visible. */
  UNKNOWN
}
