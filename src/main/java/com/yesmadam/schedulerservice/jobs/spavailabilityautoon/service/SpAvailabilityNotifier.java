package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yesmadam.schedulerservice.common.clevertap.model.CleverTapEvent;
import com.yesmadam.schedulerservice.common.clevertap.service.CleverTapEventService;

/**
 * Builds and sends the CleverTap event telling an SP their availability is on.
 *
 * <p>The payload is deliberately minimal: the SP is identified by the event's {@code identity}, and
 * {@code evtData} carries only {@code msgTitle} and {@code msgBody} for the campaign to render.
 * Those two key names are a contract with a CleverTap campaign that lives outside this repo, so a
 * rename produces a push that is accepted with a 200 and then displays nothing — {@code
 * SpAvailabilityNotifierTest} pins them for that reason.
 *
 * <p>Event name, title and body are configuration, not literals (CLAUDE.md rule 21) — changing the
 * copy an SP reads should not require a rebuild. The defaults here and the values in {@code
 * application.properties} must stay in step; the properties win, so a default changed here alone
 * would never reach any environment.
 *
 * <p>Sent by the 20:00 job ({@code SpKitApprovedNotifyWorkflowImpl}), ahead of the 23:30 flip that
 * actually switches the SP on.
 *
 * <p>No {@code io.temporal} import here either: this is a service the activity adapts to, so the
 * payload can be asserted in a plain JUnit test (rule 12).
 */
@Service
public class SpAvailabilityNotifier {

  private final CleverTapEventService cleverTapEventService;
  private final String eventName;
  private final String popTitle;
  private final String popDescription;

  public SpAvailabilityNotifier(
      CleverTapEventService cleverTapEventService,
      @Value("${sp.availability.auto-on.clevertap.event-name:SP_Campaign_Push}") String eventName,
      @Value(
              "${sp.availability.auto-on.clevertap.pop-title:Aap jobs ke liye LIVE hain \uD83D\uDEA8}")
          String popTitle,
      @Value(
              "${sp.availability.auto-on.clevertap.pop-description:"
                  + "\uD83E\uDDF3 Aapki KIT issue ho gayi hai aur...\n\uD83D\uDCF2 Aapki availability ON ho chuki hai. App open karein aur orders accept karein \u2705}")
          String popDescription) {
    this.cleverTapEventService = cleverTapEventService;
    this.eventName = eventName;
    this.popTitle = popTitle;
    this.popDescription = popDescription;
  }

  /**
   * Sends one event per SP in a single CleverTap upload.
   *
   * <p>One HTTP call for the whole batch, not one per SP — the caller's batch size is already
   * capped below CleverTap's per-upload ceiling, so this never needs to chunk again.
   *
   * @param spIds the SPs to tell; empty (or null) is a no-op rather than an empty HTTP call
   * @return number of events sent
   */
  public int notifyAvailabilityTurnedOn(List<Integer> spIds) {
    if (spIds == null || spIds.isEmpty()) {
      return 0;
    }
    return cleverTapEventService.sendEvents(spIds.stream().map(this::buildEvent).toList());
  }

  private CleverTapEvent buildEvent(Integer spId) {
    Map<String, Object> evtData = new LinkedHashMap<>();
    evtData.put("msgTitle", popTitle);
    evtData.put("msgBody", popDescription);

    // CleverTap identity for an SP is the SP id as a string, same as every other SP event we send.
    return CleverTapEvent.of(String.valueOf(spId), eventName, evtData);
  }
}
