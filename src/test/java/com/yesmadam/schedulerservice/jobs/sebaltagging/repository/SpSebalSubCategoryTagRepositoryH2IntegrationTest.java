package com.yesmadam.schedulerservice.jobs.sebaltagging.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.RankedTagProjection;

/**
 * Integration test for {@link SpSebalSubCategoryTagRepository#computeRanksForSubCategories}
 * against real H2 in MySQL compatibility mode.
 *
 * <p>Uses a {@code @DataJpaTest} slice deliberately, not a full {@code @SpringBootTest}: this
 * repository doesn't need the app's reader/writer routing datasource, AWS Secrets Manager
 * config, or Temporal wiring — pulling those in would only slow the test down and add unrelated
 * failure modes. {@code @AutoConfigureTestDatabase(replace = NONE)} stops Spring Boot from
 * substituting its own embedded database and replaces it with the H2 URL below instead, so the
 * schema comes from our own script, not Hibernate auto-DDL (kept off via
 * hibernate.ddl-auto=none — the repo's local entities and the ones from ym-common-entity mix
 * enough legacy mapping quirks that auto-DDL isn't trusted here, matching prod).
 *
 * <p>PREREQUISITE: run {@code spike.H2MySqlDialectSpikeTest} first. If it fails on either the
 * division or the {@code <=>} check, do not trust this test's green result — see that test's
 * class doc.
 *
 * <p>The five fixture rows below are chosen to hit the specific risks called out during design:
 * an exact tie (does RANK/NTILE tie-break match what the code depends on), a sub-category with
 * only 2 pooled rows (NTILE(3) forced to assign POOR to one of them regardless of rate — this is
 * documented, expected behaviour, not a bug), the eligibility-pool EXISTS filter, and the
 * first_job_users_count > 0 filter.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "spring.config.import=",
      "spring.datasource.url=jdbc:h2:mem:sebaltagging_ranking;MODE=MySQL;DB_CLOSE_DELAY=-1;"
          + "NON_KEYWORDS=VALUE,KEY,END,START",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect"
    })
@Sql(scripts = "/db/h2/sebaltagging-schema-h2.sql")
class SpSebalSubCategoryTagRepositoryH2IntegrationTest {

  @Autowired private SpSebalSubCategoryTagRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void ranksOnlyEligiblePoolSpsAndExcludesZeroSampleRows() {
    markEligible(1, 2, 3); // sp 4 is deliberately NOT in the eligibility pool
    // sub-category 100: three SPs, one ineligible (excluded), one zero-sample (excluded).
    seedTag(1, 100, 90, 45); // rate 50.00% — eligible
    seedTag(2, 100, 100, 20); // rate 20.00% — eligible
    seedTag(4, 100, 100, 90); // rate 90.00% — NOT eligible, must be excluded entirely
    seedTag(3, 100, 50, 0); // rate 0% but first_job_users_count > 0 — eligible, included
    seedTag(9, 100, 0, 0); // first_job_users_count = 0 — must be excluded by the > 0 filter

    List<RankedTagProjection> ranked =
        repository.computeRanksForSubCategories(List.of(100));

    assertThat(ranked).extracting(RankedTagProjection::getSpId).containsExactlyInAnyOrder(1, 2, 3);
    Map<Integer, RankedTagProjection> bySp =
        ranked.stream().collect(java.util.stream.Collectors.toMap(RankedTagProjection::getSpId, r -> r));
    assertThat(bySp.get(1).getRebookingRate()).isEqualByComparingTo("50.00");
    // Best rate (sp 1, 50%) must rank 1st among the eligible pool — sp 4's 90% must not count.
    assertThat(bySp.get(1).getNationalRank()).isEqualTo(1);
  }

  @Test
  void exactTieOnRebookingRateIsHandledDeterministicallyByTheDatabase() {
    markEligible(1, 2);
    // Identical rate (50.00%) for both SPs in the same sub-category.
    seedTag(1, 200, 100, 50);
    seedTag(2, 200, 100, 50);

    List<RankedTagProjection> ranked = repository.computeRanksForSubCategories(List.of(200));

    assertThat(ranked).hasSize(2);
    // RANK() over a genuine tie must give BOTH rows rank 1 (that's what RANK — as opposed to
    // ROW_NUMBER — means). If this ever comes back as 1 and 2, the production query's semantics
    // changed underneath it (or H2 and MySQL disagree on tie handling) — fail loudly either way.
    assertThat(ranked).allSatisfy(r -> assertThat(r.getNationalRank()).isEqualTo(1));
    // NTILE(3) over exactly 2 tied rows: both land in bucket 1 or split 1/2 depending on the
    // engine's internal tie-break order. Assert only what the code's own contract promises —
    // that a 2-row cohort can never contain a POOR tier, since NTILE(3) over N<3 rows never
    // reaches bucket 3. This is the assertion that would catch a real MySQL-vs-H2 NTILE
    // divergence without depending on undefined tie-break order.
    assertThat(ranked).extracting(RankedTagProjection::getTier).doesNotContain("POOR");
  }

  @Test
  void ntile3OverAnUndersizedCohortStillTagsOneRowPoorRegardlessOfRate() {
    // Documented, intentional behaviour (see the repository's own Javadoc): NTILE(3) over 3 rows
    // always produces one of each bucket, even though none of these SPs is actually a poor
    // performer in absolute terms. This test exists to make that behaviour visible and pinned,
    // not to imply it's a bug.
    markEligible(1, 2, 3);
    seedTag(1, 300, 100, 80); // 80%
    seedTag(2, 300, 100, 78); // 78%
    seedTag(3, 300, 100, 76); // 76% — best-in-class SP, still tagged POOR by construction

    List<RankedTagProjection> ranked = repository.computeRanksForSubCategories(List.of(300));

    assertThat(ranked).hasSize(3);
    assertThat(ranked).extracting(RankedTagProjection::getTier)
        .containsExactlyInAnyOrder("EXCELLENT", "MEDIUM", "POOR");
  }

  // --- fixture helpers -----------------------------------------------------------------------

  private void markEligible(int... spIds) {
    for (int spId : spIds) {
      jdbcTemplate.update(
          "insert into ysmdm_admin.tbl_sp_rose_order_eligibilities (sp_id, eligible) values (?,"
              + " true)",
          spId);
    }
  }

  private void seedTag(int spId, int subCategoryId, long firstJobUsersCount, long rebooked90dUsersCount) {
    jdbcTemplate.update(
        "insert into ysmdm_admin.tbl_sp_sebal_subcategory_tags"
            + " (sp_id, sub_category_id, first_job_users_count, rebooked_90d_users_count,"
            + " created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
        spId,
        subCategoryId,
        firstJobUsersCount,
        rebooked90dUsersCount,
        LocalDateTime.now(),
        LocalDateTime.now());
  }
}
