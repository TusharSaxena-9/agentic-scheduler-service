package com.yesmadam.schedulerservice.jobs.roseorder.service;

import static com.yesmadam.schedulerservice.jobs.roseorder.enums.RoseOrderEligibilityConfig.ASSIGN_TO_COMPLETE_RATIO;
import static com.yesmadam.schedulerservice.jobs.roseorder.enums.RoseOrderEligibilityConfig.MIN_COMPLETED_ORDER;
import static com.yesmadam.schedulerservice.jobs.roseorder.enums.RoseOrderEligibilityConfig.RATING_MIN;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.yesmadam.persistence.SPRegistration;
import com.yesmadam.persistence.SettingsForEnalbeDisable;
import com.yesmadam.persistence.SpRoseOrderEligibilityLedger;
import com.yesmadam.schedulerservice.jobs.roseorder.enums.RoseOrderEligibilityConfig;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderChunkResult;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderEligibilityThresholds;
import com.yesmadam.schedulerservice.jobs.roseorder.model.RoseOrderRunContext;
import com.yesmadam.schedulerservice.jobs.roseorder.model.SpOverallRatingProjection;
import com.yesmadam.schedulerservice.service.interfaces.SettingService;
import com.yesmadam.schedulerservice.service.interfaces.SpService;
import com.yesmadam.schedulerservice.utils.QueryTimer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RoseOrderServiceImpl implements RoseOrderService {

  /**
   * Number of most-recent rated jobs used for the average-rating gate (also the min rated count).
   */
  private static final int RATING_WINDOW = 50;

  /**
   * Rolling window (days) for the assign-to-complete ratio, matching P3Strategy's
   * {@code date.fetch.data} default (30) in ysmdm-jpe.
   */
  private static final int ASSIGN_TO_COMPLETE_WINDOW_DAYS = 30;

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final SpService spService;
  private final SettingService settingService;
  private final RoseOrderEligibilityWriter eligibilityWriter;

  public RoseOrderServiceImpl(
      SpService spService,
      SettingService settingService,
      RoseOrderEligibilityWriter eligibilityWriter) {
    this.spService = spService;
    this.settingService = settingService;
    this.eligibilityWriter = eligibilityWriter;
  }

  @Override
  public RoseOrderRunContext fetchRunContext() {
    List<Integer> spIds = spService.findAllActiveSpIds();

    Map<String, SettingsForEnalbeDisable> roseConfig =
        settingService.findSettingWithKeys(RoseOrderEligibilityConfig.keys());

    RoseOrderEligibilityThresholds thresholds =
        new RoseOrderEligibilityThresholds(
            ASSIGN_TO_COMPLETE_RATIO.decimal(roseConfig),
            RATING_MIN.decimal(roseConfig),
            MIN_COMPLETED_ORDER.intValue(roseConfig));

    log.info(
        "Rose Order run context: activeSps={} thresholds: assignToComplete>={}%, rating>={} over last {}, completedOrders>={}",
        spIds.size(),
        thresholds.getAssignToCompleteMinPct(),
        thresholds.getRatingMin(),
        RATING_WINDOW,
        thresholds.getMinCompletedOrders());

    return new RoseOrderRunContext(spIds, thresholds);
  }

  @Override
  public RoseOrderChunkResult processChunk(
      List<Integer> chunk, RoseOrderEligibilityThresholds thresholds, String runId) {

    // ---- metrics for the chunk (one read-only query each, served by the reader). Each call is
    // timed and its row count captured via `timer` below, so the breakdown rides along in this
    // activity's own result - visible in the Temporal dashboard once the activity completes,
    // without extra activities/threads/logging. ----
    QueryTimer timer = new QueryTimer();

    Map<Integer, Long> spOrderCompletionCountMap =
        timer.time("countCompletedOrdersCountForSpIDs", () -> spService.countCompletedOrdersCountForSpIDs(chunk));
    Map<Integer, SpOverallRatingProjection> spAvgRatingMap =
        timer.time("spAvgRatingForLastOrders", () -> spService.spAvgRatingForLastOrders(chunk, RATING_WINDOW));
    Map<Integer, Long> spJobCompletedCountMap =
        timer.time("jobCompletedCountPerSP", () -> spService.jobCompletedCountPerSP(chunk, ASSIGN_TO_COMPLETE_WINDOW_DAYS));
    Map<Integer, Long> spJobsRejectedCountMap =
        timer.time("jobsRejectedCount", () -> spService.jobsRejectedCount(chunk, ASSIGN_TO_COMPLETE_WINDOW_DAYS));
    Map<Integer, Long> spJobsMarkedDefaulterCountMap =
        timer.time("jobsMarkedDefaulterCount", () -> spService.jobsMarkedDefaulterCount(chunk, ASSIGN_TO_COMPLETE_WINDOW_DAYS));
    Map<Integer, Long> spJobsCancelButSuccessCountMap =
        timer.time("jobsCancelButJobSuccessCount", () -> spService.jobsCancelButJobSuccessCount(chunk, ASSIGN_TO_COMPLETE_WINDOW_DAYS));
    Map<Integer, Long> spJobsImpactedBySPLeaveCountMap =
        timer.time("jobsImpactedBySPLeaveCount", () -> spService.jobsImpactedBySPLeaveCount(chunk, ASSIGN_TO_COMPLETE_WINDOW_DAYS));

    Map<Integer, SpRoseOrderEligibilityLedger> ledgersBySpId = new LinkedHashMap<>();
    int eligibleCount = 0;

    for (Integer spId : chunk) {

      long completedAllTime = spOrderCompletionCountMap.getOrDefault(spId, 0L);
      SpOverallRatingProjection ratingProjection = spAvgRatingMap.get(spId);
      long completed30d = spJobCompletedCountMap.getOrDefault(spId, 0L);
      long rejected30d = spJobsRejectedCountMap.getOrDefault(spId, 0L);
      long markedDefaulter30d = spJobsMarkedDefaulterCountMap.getOrDefault(spId, 0L);
      long cancelButJobSuccess30d = spJobsCancelButSuccessCountMap.getOrDefault(spId, 0L);
      long impactedBySPLeave30d = spJobsImpactedBySPLeaveCountMap.getOrDefault(spId, 0L);

      // --- Param 1: assign -> complete ratio over last 30 days (percentage) ---
      long assigned30d =
          completed30d + rejected30d + markedDefaulter30d + cancelButJobSuccess30d
              + impactedBySPLeave30d;
      BigDecimal assignToCompleteRatio =
          assigned30d > 0
              ? BigDecimal.valueOf(completed30d)
                  .multiply(HUNDRED)
                  .divide(BigDecimal.valueOf(assigned30d), 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
      boolean assignToCompletePassed =
          assigned30d > 0
              && assignToCompleteRatio.compareTo(thresholds.getAssignToCompleteMinPct()) >= 0;

      // --- Param 2: average of last N rated jobs (< N rated => ineligible) ---
      long ratedInWindow =
          ratingProjection != null && ratingProjection.getRatedInWindow() != null
              ? ratingProjection.getRatedInWindow()
              : 0L;
      BigDecimal averageRating =
          ratingProjection != null && ratingProjection.getAvgRating() != null
              ? BigDecimal.valueOf(ratingProjection.getAvgRating())
                  .setScale(2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;
      boolean averageRatingPassed =
          ratedInWindow >= RATING_WINDOW && averageRating.compareTo(thresholds.getRatingMin()) >= 0;

      // --- Param 3: all-time completed orders ---
      boolean completedOrderPassed = completedAllTime >= thresholds.getMinCompletedOrders();

      // --- Overall: all three gates must pass ---
      boolean eligible = assignToCompletePassed && averageRatingPassed && completedOrderPassed;

      SPRegistration spRef = spService.getReferenceForSpId(spId);

      // ---- audit ledger (append one row per run; also carries the current-state flags) ----
      SpRoseOrderEligibilityLedger ledger = new SpRoseOrderEligibilityLedger();
      ledger.setSp(spRef);
      ledger.setRunId(runId);
      ledger.setAssignedToCompleteRatio(assignToCompleteRatio);
      ledger.setAssignedToCompleteThresholdPassed(assignToCompletePassed);
      ledger.setAverageRating(averageRating);
      ledger.setAverageRatingOrderCount(ratedInWindow);
      ledger.setAverageRatingThresholdPassed(averageRatingPassed);
      ledger.setCompletedOrdersCount(completedAllTime);
      ledger.setCompletedOrdersCountPassed(completedOrderPassed);
      ledger.setOrderAssigned(assigned30d);
      ledger.setOrderCompleted(completed30d);
      ledger.setEligible(eligible);
      ledgersBySpId.put(spId, ledger);

      if (eligible) {
        eligibleCount++;
      }
    }

    // One transaction per chunk: current-state upsert + ledger append commit together on the
    // writer, so the two tables cannot diverge and the upsert never reads a lagging replica.
    eligibilityWriter.persistChunk(ledgersBySpId);

    return new RoseOrderChunkResult(
        chunk.size(), eligibleCount, timer.getDurationsMs(), timer.getRowCounts());
  }
}
