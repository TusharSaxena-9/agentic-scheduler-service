package com.yesmadam.schedulerservice.jobs.sebaltagging.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SpSebalSubCategoryTag;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.RankedTagProjection;

/**
 * Repository for the current-state running aggregate ({@code tbl_sp_sebal_subcategory_tags}).
 *
 * <p>Read-only by design: ranking is a read ({@link #computeRanksForSubCategories}), not a bulk
 * {@code UPDATE} — see that method's doc for why. Every write to this table goes through {@code
 * SebalTaggingBatchDao} as a multi-row statement, including the per-chunk merge, which is why
 * there is no longer a {@code findBySp_IdIn} lookup here: MySQL merges the counts against the
 * {@code (sp_id, sub_category_id)} unique constraint instead of Java reading them back first.
 */
@Repository
public interface SpSebalSubCategoryTagRepository extends JpaRepository<SpSebalSubCategoryTag, Long> {

  /**
   * Rate/rank/tier for every non-zero-sample row in the given sub-categories only — scoped to
   * whichever sub-categories this run's chunks actually touched, not the whole table, so a
   * quiet night's re-tiering pass doesn't re-sort sub-categories nothing happened in.
   *
   * <p><b>Ranking is scoped to the Rose Order SP pool</b> — rows whose SP has no
   * {@code tbl_sp_rose_order_eligibilities} entry are excluded, so {@code RANK()} and
   * {@code NTILE(3)} compute percentiles over pooled SPs only. {@code WHERE} is evaluated before
   * window functions, which is what makes that scoping work without a subquery.
   *
   * <p>This is a deliberate deviation from PRD 2.2, which specifies ranking over the overall SP
   * pool ("All SPs are ranked nationally", "Global percentile logic") with eligibility applied
   * later as an assignment-time filter (Module 5 Step 1 → Step 2). Ranking within the pool instead
   * gives an even Excellent/Medium/Poor split across the SPs actually assignable, rather than the
   * upper-tier clustering you get when a better-than-average pool is ranked against everyone. Two
   * consequences worth knowing:
   *
   * <ul>
   *   <li>PRD 2.4's acceptance criteria are worded on national percentile ("middle 33%
   *       (33rd–66th percentile) nationally"), so they no longer describe this behaviour.
   *   <li>Percentile bands are only meaningful where a sub-category has enough pooled SPs.
   *       {@code NTILE(3)} over three rows tags one of them Poor regardless of its rate.
   * </ul>
   *
   * <p>Membership is tested by row <em>presence</em>, and that is sufficient on its own:
   * {@code RoseOrderEligibilityWriter} only ever inserts SPs that passed all three criteria and
   * never removes them ("once qualified should not be removed from pool"), so every row in that
   * table carries {@code eligible = true} and the flag adds nothing to test against.
   *
   * <p>Presence therefore already implies the SP was working: PRD 2.1 Parameter 1 requires ≥85%
   * assign-to-complete over the <em>last 30 days</em>, which is impossible to clear without
   * assigned jobs in that window — the PRD spells this out, "Zero assigned jobs in the last 30 days
   * (new or inactive SP) → parameter fails, SP is ineligible". That is what satisfies PRD 2.2's
   * "For each Active SP"; do <b>not</b> add a current-SP-status filter on top. It would drop SPs
   * that qualified and later went quiet, which the never-remove-from-pool requirement deliberately
   * keeps.
   *
   * <p>Cooldown is deliberately not considered here either: a 3-day cooldown must not re-cut
   * national bands.
   *
   * <p>Note this makes finalize depend on the Rose Order eligibility job having populated that
   * table. An empty pool yields zero ranked rows and therefore no tiers at all — see
   * {@code SebalTaggingServiceImpl.finalizeRun}, which logs a warning for exactly that case.
   *
   * <p>A plain {@code SELECT}, not a bulk {@code UPDATE}: at 50k+ SPs across hundreds of
   * sub-categories, one long-held write transaction over everything it touches is exactly the
   * kind of statement that shows up as a replication-lag spike. Computing the new values here
   * and applying them in small batches (see {@code SebalTaggingWriter.applyRankBatch}) keeps
   * each actual write short — and since this computation is a pure function of the current
   * counts, re-running it after a partial-batch failure just reproduces the same answer.
   *
   * <p>{@code NTILE(3)} bucket 1/2/3 maps to Excellent/Medium/Poor.
   */
  @Query(
      value =
          """
    select t.id,
           t.sp_id                                  as spId,
           t.sub_category_id                        as subCategoryId,
           t.first_job_users_count                  as firstJobUsersCount,
           t.rebooked_90d_users_count               as rebooked90dUsersCount,
           round(t.rebooked_90d_users_count / t.first_job_users_count * 100, 2) as rebookingRate,
           rank() over (
             partition by t.sub_category_id
             order by t.rebooked_90d_users_count / t.first_job_users_count desc
           )                                        as nationalRank,
           case ntile(3) over (
             partition by t.sub_category_id
             order by t.rebooked_90d_users_count / t.first_job_users_count desc
           )
             when 1 then 'EXCELLENT'
             when 2 then 'MEDIUM'
             else 'POOR'
           end                                      as tier
    from ysmdm_admin.tbl_sp_sebal_subcategory_tags t
    where t.sub_category_id in (:subCategoryIds)
      and t.first_job_users_count > 0
      and exists (
        select 1
        from ysmdm_admin.tbl_sp_rose_order_eligibilities e
        where e.sp_id = t.sp_id
      )
    """,
      nativeQuery = true)
  List<RankedTagProjection> computeRanksForSubCategories(
      @Param("subCategoryIds") List<Integer> subCategoryIds);

  long countByTierIsNotNull();

  @Query(
      value =
          "select count(distinct sub_category_id) from ysmdm_admin.tbl_sp_sebal_subcategory_tags"
              + " where tier is not null",
      nativeQuery = true)
  long countDistinctTieredSubCategories();
}
