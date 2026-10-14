package com.yesmadam.schedulerservice.jobs.raindelay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Which configured hubs have imminent bookings, and the weather at each. Bounded by the size of the
 * {@code rainHub} row, so safe to carry across the activity boundary in full.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RainyHubResolution {

  private int configuredHubCount;

  /** Configured hubs that actually have a booking in the window. */
  private int candidateHubCount;

  /**
   * Candidate hubs whose lookup failed. Counted, not listed — nothing reads which ones, and it must
   * stay distinct from "clear" so a provider outage is visible instead of looking like dry weather.
   */
  private int unknownHubCount;

  /** Hubs over the precipitation threshold. These notify. */
  private List<Integer> rainyHubIds;

  /** Window actually queried, {@code yyyy-MM-dd HH:mm:ss} IST. Computed in the activity. */
  private String windowStart;

  private String windowEnd;

  private Map<String, Long> queryDurationsMs;

  private Map<String, Integer> queryRowCounts;

  public static RainyHubResolution empty(
      int configuredHubCount, String windowStart, String windowEnd) {
    return new RainyHubResolution(
        configuredHubCount,
        0,
        0,
        List.of(),
        windowStart,
        windowEnd,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }
}
