package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.SpUpdateActionHistory;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.SpAvailabilityRepository;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.SpUpdateActionHistoryRepository;

/**
 * Turns availability on for one batch of SPs and records why, as a single committing unit.
 *
 * <p>The history row's action name comes from {@link SpKitApprovalActions}, not a literal here —
 * the notify job's dedup query filters on the sibling constant, and two copies of these strings
 * drifting apart would be silent: a filter that matches nothing simply stops deduplicating.
 *
 * <p>One transaction per batch rather than one per run (CLAUDE.md rule 14): a failure on batch 7 of
 * 20 keeps the first six, and the retry resumes rather than redoing them. The transaction covers
 * only the two writes — the reader's query and the window arithmetic happen outside it, so no
 * connection is held while the job is doing anything that is not a database operation (rule 13).
 */
@Service
public class SpAvailabilityAutoOnWriter {

  /** Both written as strings, matching how admin and messagingapis write this table. */
  private static final String PREVIOUS_VALUE = "false";

  private static final String CURRENT_VALUE = "true";

  private final SpAvailabilityRepository availabilityRepository;
  private final SpUpdateActionHistoryRepository historyRepository;

  public SpAvailabilityAutoOnWriter(
      SpAvailabilityRepository availabilityRepository,
      SpUpdateActionHistoryRepository historyRepository) {
    this.availabilityRepository = availabilityRepository;
    this.historyRepository = historyRepository;
  }

  /**
   * Flips one batch on and appends one history row per SP actually flipped.
   *
   * <p>Idempotent under retry without a marker row, because both the re-check and the update carry
   * the same {@code availability is null or false} guard: a retry after this transaction committed
   * finds nothing left to match, updates nothing, and writes no history. That is the "commits, then
   * the activity dies before Temporal records completion" case from rule 14 — the one a transaction
   * on its own does not protect against.
   *
   * <p>The re-check is what keeps the history honest. Candidate ids come from the read replica,
   * which can lag behind a flip an earlier batch in this same run already committed, and an SP can
   * also be switched off by an operator between the read and the write. Writing history for the
   * batch as handed in would claim changes that never happened.
   *
   * <p>Returns the ids rather than a count because they are what the CleverTap send is driven from
   * — and they must be the re-checked subset, not the candidates, or the job announces to an SP a
   * change it did not make to them.
   *
   * @param candidateSpIds SP ids from the reader, already bounded to one page
   * @param actionAt timestamp written to both {@code tbl_sp.updated_at} and the history row
   * @return the SPs actually flipped, in ascending id order; never more than {@code candidateSpIds}
   */
  @Transactional
  public List<Integer> turnAvailabilityOn(List<Integer> candidateSpIds, Timestamp actionAt) {
    if (candidateSpIds.isEmpty()) {
      return List.of();
    }

    List<Integer> toFlip = availabilityRepository.findStillAvailabilityOff(candidateSpIds);
    if (toFlip.isEmpty()) {
      return List.of();
    }

    availabilityRepository.turnAvailabilityOn(toFlip, actionAt);
    historyRepository.saveAll(buildHistory(toFlip, actionAt));
    return toFlip;
  }

  private List<SpUpdateActionHistory> buildHistory(List<Integer> spIds, Timestamp actionAt) {
    List<SpUpdateActionHistory> rows = new ArrayList<>(spIds.size());
    for (Integer spId : spIds) {
      SpUpdateActionHistory row = new SpUpdateActionHistory();
      row.setSpId(spId);
      row.setPrevious(PREVIOUS_VALUE);
      row.setCurrent(CURRENT_VALUE);
      row.setAction(SpKitApprovalActions.AVAILABILITY_TURNED_ON);
      row.setActionAt(actionAt);
      // actionBy stays null: there is no employee behind this change, and inventing an id would
      // attribute it to a real person. messagingapis' InstantJobAvailabilityCron does the same.
      rows.add(row);
    }
    return rows;
  }
}
