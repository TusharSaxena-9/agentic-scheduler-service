package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;
import com.yesmadam.schedulerservice.utils.QueryTimer;

/**
 * One page of the notify run: read a page of unclaimed eligible SPs on the replica, claim it on the
 * primary in its own transaction, hand back who to push to.
 *
 * <p>Deliberately not {@code @Transactional} itself — it reads, then writes, then reports, and
 * wrapping that would hold one writer connection for all three and route the read onto the primary
 * with it (rule 13). The transaction lives on {@link
 * SpKitApprovedNotifyWriter#claimForNotification}.
 *
 * <p>Mirrors {@link SpAvailabilityAutoOnServiceImpl} step for step. The two jobs walk the same
 * cohort with the same keyset loop and the same batch size; they differ only in what a page means —
 * claimed here, flipped there — which is why the page shape is one shared {@link SpKitCohortBatch}.
 *
 * <p><b>Retried at 99% complete</b> (rule 14): a retry re-runs this for the same cursor, the
 * reader's {@code NOT EXISTS} no longer returns the SPs the first attempt claimed, and the writer's
 * own re-check catches any the replica has not caught up on. Either way nothing is claimed twice,
 * so nothing is pushed twice.
 */
@Service
public class SpKitApprovedNotifyServiceImpl implements SpKitApprovedNotifyService {

  /** Query names under which timings are recorded; a bounded set of literals (rule 15). */
  private static final String QUERY_FIND_UNNOTIFIED = "findUnnotifiedEligibleSpIds";

  private static final String QUERY_CLAIM = "claimForNotification";

  private final SpAvailabilityAutoOnReader reader;
  private final SpKitApprovedNotifyWriter writer;

  /**
   * Shared with the availability job on purpose: the two walk the same cohort, and a mismatch would
   * mean the 20:00 run and the 23:30 run page it differently for no reason. Capped below
   * CleverTap's 1000-events-per-upload ceiling so a batch is always one HTTP call.
   */
  private final int batchSize;

  public SpKitApprovedNotifyServiceImpl(
      SpAvailabilityAutoOnReader reader,
      SpKitApprovedNotifyWriter writer,
      @Value("${sp.availability.auto-on.batch-size:200}") int batchSize) {
    this.reader = reader;
    this.writer = writer;
    this.batchSize = batchSize;
  }

  @Override
  public SpKitCohortBatch claimNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId) {
    // A fresh timer per batch — never a field on this singleton bean (rule 15). Its numbers travel
    // back on the result and into workflow history, which is the only place per-run query timings
    // are readable; nothing aggregates them across runs.
    QueryTimer timer = new QueryTimer();

    List<Integer> candidates =
        timer.time(
            QUERY_FIND_UNNOTIFIED,
            (QueryTimer.ListSupplier<Integer>)
                () ->
                    reader.findUnnotifiedEligibleSpIds(
                        window.getWindowStart(), window.getWindowEnd(), afterSpId, batchSize));

    if (candidates.isEmpty()) {
      return new SpKitCohortBatch(
          List.of(), 0, 0, true, timer.getDurationsMs(), timer.getRowCounts());
    }

    List<Integer> claimed =
        timer.time(
            QUERY_CLAIM,
            (QueryTimer.ListSupplier<Integer>)
                () ->
                    writer.claimForNotification(
                        candidates, window.getActionAt(), window.getWindowStart()));

    return new SpKitCohortBatch(
        claimed,
        candidates.size(),
        candidates.get(candidates.size() - 1),
        // A short page means the reader had nothing more to give.
        candidates.size() < batchSize,
        timer.getDurationsMs(),
        timer.getRowCounts());
  }
}
