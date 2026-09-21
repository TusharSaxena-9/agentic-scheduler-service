package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.ProductKitAssignToSp;

/**
 * The read half of the auto-availability-on job: which SPs had their <b>first</b> kit assignment
 * approved in the window and are still sitting with availability off.
 *
 * <p><b>First, not any.</b> The derived table picks {@code MIN(id)} per SP — the earliest kit
 * assignment row ever written for them — and the window filter then applies to <em>that row
 * only</em>. So an SP who has been active for a year and had a replacement kit approved yesterday
 * does not match; only an SP whose original assignment was approved yesterday does. That is the
 * intended reading of "auto-enable on kit approval": it fires once, when an SP is first equipped,
 * not every time they are re-equipped.
 *
 * <p>Two consequences of keying on {@code MIN(id)} that are worth knowing before reading a run's
 * numbers:
 *
 * <ul>
 *   <li>{@code MIN(id)} is the first row <em>inserted</em>, not the first <em>approved</em>. An SP
 *       whose original assignment was never approved — cancelled, superseded, left with a null
 *       {@code approvedat} — is matched against that null row and therefore never qualifies, even
 *       if a later assignment was approved yesterday. If those SPs should qualify, the subquery
 *       needs {@code WHERE approvedat IS NOT NULL} inside it; that is a change of rule, not a fix,
 *       so it is written down rather than assumed.
 *   <li>The subquery is unbounded by the window on purpose — "first assignment per SP" is a global
 *       fact, so it cannot be narrowed to yesterday. See the access path below for what that costs.
 * </ul>
 *
 * <p>No {@code DISTINCT} is needed: joining on {@code MIN(id)} already yields at most one row per
 * SP, so an SP with several kits approved the same day is offered once.
 *
 * <p><b>Native rather than HQL</b> (rule 16), and this is one of the cases the rule carves out: the
 * query joins a derived table ({@code INNER JOIN (SELECT ... GROUP BY ...)}), and JPQL has no
 * subquery-in-FROM at all. There is no HQL spelling of this to prefer.
 *
 * <p><b>The window bound is inclusive at both ends</b> ({@code BETWEEN}), while the job's window is
 * built half-open — {@code windowEnd} is today 00:00:00. An approval landing on that exact instant
 * therefore matches both today's run and tomorrow's. Harmless here rather than a double-count: the
 * writer's {@code availability IS NULL OR = false} guard means the second day finds the SP already
 * on and flips nothing, so the only cost is one wasted candidate. Swap to {@code >= :windowStart
 * AND < :windowEnd} if that ever stops being true.
 *
 * <p><b>Access path (rule 15) — read this before enabling the schedule.</b> The derived table
 * aggregates {@code tbl_product_kit_assign_to_sp} in full on every run: {@code GROUP BY sp_id} over
 * the whole table, with no window to narrow it. That cost scales with the table, not with how many
 * kits were approved yesterday, and it is paid whether or not any SP qualifies. Two indexes are
 * wanted, and neither exists as of this writing:
 *
 * <pre>{@code
 * -- Lets the GROUP BY resolve as a loose index scan instead of a full scan into a temp table.
 * ALTER TABLE ysmdm_admin.tbl_product_kit_assign_to_sp ADD INDEX idx_sp_id_id (sp_id, id);
 *
 * -- Lets the optimizer drive from the window instead, when yesterday's slice is the smaller side.
 * ALTER TABLE ysmdm_admin.tbl_product_kit_assign_to_sp ADD INDEX idx_approvedat_sp (approvedat, sp_id);
 * }</pre>
 *
 * <p>Check the plan with {@code EXPLAIN} once both are in place — if the optimizer still
 * materialises the full aggregate per page, the fix is to run the subquery once per <em>run</em>
 * rather than once per page, which means restructuring this into a temp table or a first-page
 * snapshot. The join to {@code tbl_sp} is a primary-key lookup either way and is not the concern.
 */
@Repository
public interface ProductKitApprovalRepository extends JpaRepository<ProductKitAssignToSp, Integer> {

  /**
   * SP ids whose first kit assignment was approved inside the window, who are active and still have
   * availability off, in ascending id order after {@code afterSpId}.
   *
   * <p>Keyset pagination on {@code sp_id} rather than {@code OFFSET}: the caller flips availability
   * on between pages, so an offset-based scan would shift rows underneath itself and skip SPs. A
   * strictly increasing cursor visits each SP at most once per run and terminates regardless of
   * what the writer has already committed, or of replica lag between the two (rule 13). The {@code
   * ORDER BY} lives in the statement rather than in the {@code Pageable}'s {@code Sort}, because
   * Spring Data cannot reliably inject a sort into a native query — it only appends {@code
   * LIMIT/OFFSET}.
   *
   * @param windowStart lower bound on {@code approvedat}, inclusive
   * @param windowEnd upper bound on {@code approvedat}, inclusive — see the class javadoc
   * @param afterSpId exclusive lower bound on the returned ids; pass {@code 0} for the first page
   * @param pageable supplies the page size only; the ordering is fixed by the query
   * @return up to one page of eligible SP ids, ascending
   */
  @Query(
      value =
          """
          SELECT t.sp_id AS spId
          FROM ysmdm_admin.tbl_product_kit_assign_to_sp t
          INNER JOIN (
              SELECT sp_id, MIN(id) AS min_id
              FROM ysmdm_admin.tbl_product_kit_assign_to_sp
              GROUP BY sp_id
          ) sub ON t.id = sub.min_id
          INNER JOIN ysmdm_admin.tbl_sp sp ON sp.id = t.sp_id
          WHERE t.approvedat IS NOT NULL
            AND t.approvedat BETWEEN :windowStart AND :windowEnd
            AND t.sp_id > :afterSpId
            AND sp.status = true
            AND (sp.availability IS NULL OR sp.availability = false)
          ORDER BY t.sp_id ASC
          """,
      nativeQuery = true)
  List<Integer> findEligibleSpIds(
      @Param("windowStart") Timestamp windowStart,
      @Param("windowEnd") Timestamp windowEnd,
      @Param("afterSpId") Integer afterSpId,
      Pageable pageable);

  /**
   * The same cohort, minus SPs this run's notify job has already pushed to.
   *
   * <p>The 20:00 notify job does not change any state the eligibility query reads — unlike the
   * 23:30 flip, whose {@code availability} guard makes it self-consuming. Without the {@code NOT
   * EXISTS} below, a retried activity or a catchup fire would re-push everyone it had already
   * pushed, and a CleverTap event cannot be taken back.
   *
   * <p>The marker is a row in {@code tbl_sp_update_action_hstry} with this run's action name, dated
   * inside the window's own day. Reusing that table rather than adding one keeps the push on the
   * SP's own timeline, where an operator asking "why did this SP get a notification" can see it.
   *
   * <p><b>Read-then-claim, not an atomic upsert.</b> {@code tbl_sp_update_action_hstry} is
   * append-only with no unique constraint to claim against, so two concurrent runs could both pass
   * this filter for the same SP. That is acceptable here and nowhere near as loose as it sounds:
   * the schedule is SKIP-overlap, so there is one run at a time, and an activity's retries are
   * serialised — the only caller is a loop that reads and claims inside one transaction before the
   * next page is read. A second concurrent runner of this job would need a second schedule, which
   * is what rule 7's orphan check exists to catch.
   *
   * <p><b>Access path (rule 15):</b> as {@link #findEligibleSpIds}, plus a correlated existence
   * check per surviving row against {@code tbl_sp_update_action_hstry}. That table is large and the
   * check filters on {@code (sp_id, action, action_at)}, none of which is indexed together today:
   *
   * <pre>{@code
   * ALTER TABLE ysmdm_admin.tbl_sp_update_action_hstry
   *   ADD INDEX idx_sp_action_at (spId, action, actionAt);
   * }</pre>
   *
   * Without it this degrades to a scan per candidate row. The candidate set is small (one day of
   * first-time kit approvals), so it is survivable at current volumes — but it is the first thing
   * to look at if this job's page time grows.
   *
   * @param windowStart lower bound on {@code approvedat}, inclusive
   * @param windowEnd upper bound on {@code approvedat}, inclusive
   * @param notifiedAction marker action name written by the notify job
   * @param afterSpId exclusive lower bound on the returned ids; pass {@code 0} for the first page
   * @param pageable supplies the page size only; the ordering is fixed by the query
   * @return up to one page of SP ids eligible and not yet notified for this window, ascending
   */
  @Query(
      value =
          """
          SELECT t.sp_id AS spId
          FROM ysmdm_admin.tbl_product_kit_assign_to_sp t
          INNER JOIN (
              SELECT sp_id, MIN(id) AS min_id
              FROM ysmdm_admin.tbl_product_kit_assign_to_sp
              GROUP BY sp_id
          ) sub ON t.id = sub.min_id
          INNER JOIN ysmdm_admin.tbl_sp sp ON sp.id = t.sp_id
          WHERE t.approvedat IS NOT NULL
            AND t.approvedat BETWEEN :windowStart AND :windowEnd
            AND t.sp_id > :afterSpId
            AND sp.status = true
            AND (sp.availability IS NULL OR sp.availability = false)
            AND NOT EXISTS (
                SELECT 1
                FROM ysmdm_admin.tbl_sp_update_action_hstry h
                WHERE h.spId = t.sp_id
                  AND h.action = :notifiedAction
                  AND h.actionAt >= :windowStart
            )
          ORDER BY t.sp_id ASC
          """,
      nativeQuery = true)
  List<Integer> findUnnotifiedEligibleSpIds(
      @Param("windowStart") Timestamp windowStart,
      @Param("windowEnd") Timestamp windowEnd,
      @Param("notifiedAction") String notifiedAction,
      @Param("afterSpId") Integer afterSpId,
      Pageable pageable);
}
