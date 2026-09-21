package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;
import com.yesmadam.schedulerservice.utils.QueryTimer;

/**
 * One batch of yesterday's kit approvals: read a page on the replica, flip it on the primary in its
 * own transaction, hand back the ids that actually changed.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional} itself. It reads, then writes, then reports —
 * wrapping that would hold one writer connection for all three and route the read onto the primary
 * with it (CLAUDE.md rule 13). The transaction lives on {@link
 * SpAvailabilityAutoOnWriter#turnAvailabilityOn}, the smallest unit that must commit together.
 *
 * <p>The loop across batches lives in the workflow rather than here, because between two batches
 * sits an outbound CleverTap call that must not share a retry boundary with a database write (rule
 * 14). That makes each batch its own activity, and the cursor a value Temporal persists in history
 * rather than one held in a local variable that a worker restart would lose.
 *
 * <p><b>Retried at 99% complete</b> (rule 14): a retry re-runs this method for the same cursor. The
 * eligibility query is self-consuming — SPs the first attempt flipped no longer match {@code
 * availability is null or false} — so they are neither flipped again nor given a second history
 * row. They are also not returned again, which means they are not re-notified; that is the intended
 * trade, since a CleverTap event cannot be taken back.
 */
@Service
public class SpAvailabilityAutoOnServiceImpl implements SpAvailabilityAutoOnService {

  /** Query names under which timings are recorded; a bounded set of literals (rule 15). */
  private static final String QUERY_FIND_ELIGIBLE = "findEligibleSpIds";

  private static final String QUERY_TURN_ON = "turnAvailabilityOn";

  private final SpAvailabilityAutoOnReader reader;
  private final SpAvailabilityAutoOnWriter writer;

  /**
   * SP ids fetched, flipped, audited and announced per batch. 500 keeps the {@code IN (...)} list
   * far below the ~20k point at which MySQL abandons the index on this shape (the measurement is
   * written up on {@code SebalTaggingCohortRepository}), keeps each transaction short enough not to
   * hold a writer connection while anything else queues behind it, and stays under CleverTap's
   * 1000-event per-upload ceiling so a batch is always one HTTP call.
   */
  private final int batchSize;

  public SpAvailabilityAutoOnServiceImpl(
      SpAvailabilityAutoOnReader reader,
      SpAvailabilityAutoOnWriter writer,
      @Value("${sp.availability.auto-on.batch-size:200}") int batchSize) {
    this.reader = reader;
    this.writer = writer;
    this.batchSize = batchSize;
  }

  @Override
  public SpAvailabilityAutoOnWindow resolveWindow(LocalDate runDate) {
    // Yesterday, whole day. Inclusive at both ends, because the eligibility query filters with
    // BETWEEN — the end is the last second of the day rather than midnight of the next one, so no
    // approval falls in two consecutive windows.
    return new SpAvailabilityAutoOnWindow(
        Timestamp.valueOf(runDate.minusDays(2).atStartOfDay()),
        Timestamp.valueOf(runDate.minusDays(2).atTime(23, 59, 59)),
        new Timestamp(System.currentTimeMillis()));
  }

  @Override
  public SpKitCohortBatch flipNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId) {
    // A fresh timer per batch — never a field on this singleton bean, which activity executions
    // share (rule 15). Its numbers travel back on the result and into workflow history, which is
    // the only place per-run query timings are readable; nothing aggregates them across runs.
    QueryTimer timer = new QueryTimer();

    List<Integer> candidates =
        timer.time(
            QUERY_FIND_ELIGIBLE,
            (QueryTimer.ListSupplier<Integer>)
                () ->
                    reader.findEligibleSpIds(
                        window.getWindowStart(), window.getWindowEnd(), afterSpId, batchSize));

    if (candidates.isEmpty()) {
      return new SpKitCohortBatch(
          List.of(), 0, 0, true, timer.getDurationsMs(), timer.getRowCounts());
    }

    // The List-returning overload records the flipped count as this query's row count, which is
    // exactly the number worth reading next to its duration.
    List<Integer> flipped =
        timer.time(
            QUERY_TURN_ON,
            (QueryTimer.ListSupplier<Integer>)
                () -> writer.turnAvailabilityOn(candidates, window.getActionAt()));

    return new SpKitCohortBatch(
        flipped,
        candidates.size(),
        candidates.get(candidates.size() - 1),
        // A short page means the reader had nothing more to give: the window is exhausted and the
        // caller can stop without paying for a round-trip that would come back empty.
        candidates.size() < batchSize,
        timer.getDurationsMs(),
        timer.getRowCounts());
  }

  @Override
  public SpAvailabilityAutoOnWindow resolveWindowNotification(LocalDate runDate) {
    // Yesterday, whole day. Inclusive at both ends, because the eligibility query filters with
    // BETWEEN — the end is the last second of the day rather than midnight of the next one, so no
    // approval falls in two consecutive windows.
    return new SpAvailabilityAutoOnWindow(
        Timestamp.valueOf(runDate.minusDays(1).atStartOfDay()),
        Timestamp.valueOf(runDate.minusDays(1).atTime(23, 59, 59)),
        new Timestamp(System.currentTimeMillis()));
  }
}
