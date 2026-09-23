package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.SpUpdateActionHistory;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.SpUpdateActionHistoryRepository;

/**
 * Claims a batch of SPs for notification, so each is pushed at most once per window.
 *
 * <p>The claim is the marker row itself: inserting {@link
 * SpKitApprovalActions#KIT_APPROVED_NOTIFIED} into {@code tbl_sp_update_action_hstry} is what makes
 * the eligibility query stop returning that SP. One transaction per batch (rule 14), so a failure
 * on page 7 keeps the first six claimed and the retry resumes.
 *
 * <p><b>Claim first, then send — and accept that this can under-send.</b> The alternative orderings
 * are worse. Sending first and marking after re-pushes everyone if the marker write fails, and a
 * CleverTap event cannot be taken back. Claiming and sending in one activity means a transient send
 * failure is retried against an already-claimed batch, which then sends nothing at all. Claiming
 * here and sending from a separate activity afterwards means the only loss case is a worker dying
 * in the gap between the two, which costs those SPs their heads-up but never sends it twice. That
 * is rule 14's stated preference, applied to the one step that cannot be rolled back.
 */
@Service
public class SpKitApprovedNotifyWriter {

  /**
   * A claim records that a push was sent, not that a value changed — nothing about the SP is
   * different yet, the flip comes at 23:30. Both sides are written as "false" so the row reads as
   * the no-op it is on a timeline that renders previous → current.
   */
  private static final String NO_CHANGE = "false";

  private final SpUpdateActionHistoryRepository historyRepository;

  public SpKitApprovedNotifyWriter(SpUpdateActionHistoryRepository historyRepository) {
    this.historyRepository = historyRepository;
  }

  /**
   * Marks the SPs that have not been claimed yet, and returns exactly those.
   *
   * <p>Re-checks on the writer before inserting. The candidates came from the read replica, which
   * can lag behind a claim an earlier page of this same run already committed; without the re-check
   * a lagging replica would re-offer an SP and this method would push them a second time.
   *
   * @param candidateSpIds SP ids from the reader, already bounded to one page
   * @param actionAt timestamp written on the marker row
   * @param windowStart lower bound for "already claimed" — the window's own start, so a marker from
   *     an earlier window does not suppress a genuinely new approval
   * @return the SPs newly claimed, and therefore the ones to push to
   */
  @Transactional
  public List<Integer> claimForNotification(
      List<Integer> candidateSpIds, Timestamp actionAt, Timestamp windowStart) {
    if (candidateSpIds.isEmpty()) {
      return List.of();
    }

    Set<Integer> alreadyClaimed =
        new HashSet<>(
            historyRepository.findSpIdsWithActionSince(
                candidateSpIds, SpKitApprovalActions.KIT_APPROVED_NOTIFIED, windowStart));

    List<Integer> toClaim = new ArrayList<>(candidateSpIds.size());
    for (Integer spId : candidateSpIds) {
      if (!alreadyClaimed.contains(spId)) {
        toClaim.add(spId);
      }
    }
    if (toClaim.isEmpty()) {
      return List.of();
    }

    historyRepository.saveAll(buildMarkers(toClaim, actionAt));
    return toClaim;
  }

  private List<SpUpdateActionHistory> buildMarkers(List<Integer> spIds, Timestamp actionAt) {
    List<SpUpdateActionHistory> rows = new ArrayList<>(spIds.size());
    for (Integer spId : spIds) {
      SpUpdateActionHistory row = new SpUpdateActionHistory();
      row.setSpId(spId);
      row.setPrevious(NO_CHANGE);
      row.setCurrent(NO_CHANGE);
      row.setAction(SpKitApprovalActions.KIT_APPROVED_NOTIFIED);
      row.setActionAt(actionAt);
      // actionBy stays null: no employee did this, and inventing an id would attribute an
      // automated push to a real person on that SP's timeline.
      rows.add(row);
    }
    return rows;
  }
}
