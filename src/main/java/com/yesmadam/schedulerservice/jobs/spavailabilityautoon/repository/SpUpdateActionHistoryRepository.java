package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SpUpdateActionHistory;

/**
 * Appends to {@code tbl_sp_update_action_hstry} — the SP's own change timeline, the same table the
 * admin panel writes when an operator toggles availability by hand ({@code
 * SPController#changeStatus}) and the same one messagingapis' {@code InstantJobAvailabilityCron}
 * writes for its automated flips.
 *
 * <p>Append-only, so a duplicate row would be noise rather than corruption (rule 13's "match the
 * guarantee to the consequence"). It is nonetheless written in the same transaction as the flip it
 * describes, because the pair is what makes the timeline trustworthy: a flip without its row looks
 * to an operator like the value changed on its own.
 *
 * <p>{@code saveAll} here is one {@code INSERT} per row, not a batch — the entity uses {@code
 * GenerationType.IDENTITY}, which makes Hibernate JDBC batching impossible (the same constraint
 * {@code SebalTaggingBatchDao} works around with multi-row native SQL). At a batch of a few hundred
 * that is not worth the workaround; if this job ever writes tens of thousands of rows in a run, it
 * is.
 */
@Repository
public interface SpUpdateActionHistoryRepository
    extends JpaRepository<SpUpdateActionHistory, Integer> {

  /**
   * Of {@code spIds}, those already carrying {@code action} at or after {@code since}.
   *
   * <p>Called from inside the notify writer's transaction, so it runs on the <b>writer</b>,
   * deliberately not under {@code @Transactional(readOnly = true)} (rule 13 — a job that writes and
   * then verifies its own write must do both on the primary). The candidate ids arrive from the
   * read replica, which can lag behind a claim this same run committed a page earlier; re-checking
   * here is what stops the same SP being claimed and pushed twice within one run. Do not annotate
   * this read-only.
   *
   * @param spIds the candidate batch
   * @param action marker action name
   * @param since lower bound on {@code actionAt} — the window's own start, so yesterday's markers
   *     do not suppress today's notification for a different approval
   * @return the subset already marked
   */
  @Query(
      "select h.spId from SpUpdateActionHistory h "
          + "where h.spId in :spIds and h.action = :action and h.actionAt >= :since")
  List<Integer> findSpIdsWithActionSince(
      @Param("spIds") List<Integer> spIds,
      @Param("action") String action,
      @Param("since") Timestamp since);
}
