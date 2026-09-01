package com.yesmadam.schedulerservice.jobs.sebaltagging.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SubCategoryRebookingDelta;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.RankedTagProjection;

/**
 * Integration test for {@link SebalTaggingBatchDao} against real H2 in MySQL compatibility mode.
 *
 * <p>Deliberately does NOT bring up a Spring context — this DAO only needs a {@link JdbcTemplate},
 * so a plain JDBC {@link Connection} plus {@link SingleConnectionDataSource} is enough. That keeps
 * this test fast and avoids dragging in the app's routing datasource / secrets-manager config at
 * all, which don't apply to what's being tested here (the SQL, not the wiring).
 *
 * <p>Prerequisite this test exists specifically to check: run {@code spike.H2MySqlDialectSpikeTest}
 * first (see src/test/java/spike) and confirm the three dialect risks it isolates — integer
 * division, the {@code <=>} operator, and their combination inside {@code ON DUPLICATE KEY UPDATE}
 * — before trusting these results. If that spike test fails, this test's green result would be
 * exactly the false confidence flagged during design: don't skip it.
 *
 * <p>Schema: src/test/resources/db/h2/sebaltagging-schema-h2.sql — hand-reconstructed from the
 * production native SQL, NOT generated from the ym-common-entity entity (that entity's source
 * wasn't available while writing this). Verify column types/constraints against the real entity
 * before relying on this beyond these specific tests.
 */
class SebalTaggingBatchDaoH2IntegrationTest {

  private static final String TAGS_TABLE = "ysmdm_admin.tbl_sp_sebal_subcategory_tags";
  private static final String LEDGER_TABLE = "ysmdm_admin.tbl_sp_sebal_subcategory_tag_ledgers";
  private static final AtomicInteger DB_COUNTER = new AtomicInteger();

  private Connection conn;
  private JdbcTemplate jdbcTemplate;
  private SebalTaggingBatchDao dao;

  @BeforeEach
  void setUp() throws Exception {
    // Fresh DB name per test — a shared name across cached contexts is what caused the
    // intermittent "Schema not found" bug the reference service hit; DB_CLOSE_DELAY=-1 keeps
    // this instance alive until this test's connection closes.
    String dbName = "sebaltagging_batch_" + DB_COUNTER.incrementAndGet();
    conn =
        DriverManager.getConnection(
            "jdbc:h2:mem:"
                + dbName
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,KEY,END,START");
    String schemaScript =
        Files.readString(Path.of("src/test/resources/db/h2/sebaltagging-schema-h2.sql"));
    try (Statement st = conn.createStatement()) {
      for (String statement : schemaScript.split(";")) {
        String trimmed = statement.trim();
        if (!trimmed.isEmpty()) {
          st.execute(trimmed);
        }
      }
    }
    jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
    dao = new SebalTaggingBatchDao(jdbcTemplate);
  }

  @AfterEach
  void tearDown() throws Exception {
    conn.close();
  }

  @Test
  void incrementalModeAlwaysAddsOntoExistingCounts() {
    seedTagRow(spId(1), subCat(100), 5, 2, "run-0");

    dao.upsertTagDeltas(
        List.of(new SubCategoryRebookingDelta(1, 100, 3, 1)), SebalTaggingRunMode.INCREMENTAL,
        "run-1");

    Row row = readTagRow(1, 100);
    assertThat(row.firstJobUsersCount).isEqualTo(8); // 5 + 3
    assertThat(row.rebooked90dUsersCount).isEqualTo(3); // 2 + 1
    assertThat(row.lastRunId).isEqualTo("run-1");
  }

  @Test
  void fullModeReplacesOnFirstTouchByARunThenAddsForTheRestOfThatRun() {
    // Row was last touched by an older run ("run-0"). This chunk is the first time "run-1"
    // (a FULL run) touches this pair, so it must REPLACE, not add.
    seedTagRow(spId(1), subCat(100), 5, 2, "run-0");

    dao.upsertTagDeltas(
        List.of(new SubCategoryRebookingDelta(1, 100, 3, 1)), SebalTaggingRunMode.FULL, "run-1");
    Row afterFirstChunk = readTagRow(1, 100);
    assertThat(afterFirstChunk.firstJobUsersCount)
        .as("first FULL-run touch must replace, not add")
        .isEqualTo(3);
    assertThat(afterFirstChunk.rebooked90dUsersCount).isEqualTo(1);
    assertThat(afterFirstChunk.lastRunId).isEqualTo("run-1");

    // A second chunk of the SAME run touching the SAME pair must now ADD, per the documented
    // "replace-on-first-touch, then add for the rest of that run" semantics.
    dao.upsertTagDeltas(
        List.of(new SubCategoryRebookingDelta(1, 100, 2, 1)), SebalTaggingRunMode.FULL, "run-1");
    Row afterSecondChunk = readTagRow(1, 100);
    assertThat(afterSecondChunk.firstJobUsersCount)
        .as("second touch by the same FULL run must add onto the replaced value")
        .isEqualTo(5); // 3 + 2
    assertThat(afterSecondChunk.rebooked90dUsersCount).isEqualTo(2); // 1 + 1
  }

  @Test
  void upsertTagDeltasCreatesTheRowWhenThePairHasNeverBeenSeen() {
    dao.upsertTagDeltas(
        List.of(new SubCategoryRebookingDelta(2, 200, 4, 3)), SebalTaggingRunMode.INCREMENTAL,
        "run-1");

    Row row = readTagRow(2, 200);
    assertThat(row.firstJobUsersCount).isEqualTo(4);
    assertThat(row.rebooked90dUsersCount).isEqualTo(3);
  }

  @Test
  void resetRowsNotTouchedByRunZeroesEverythingWithADifferentOrMissingLastRunId() {
    seedTagRow(spId(1), subCat(100), 5, 2, "run-0"); // stale — different run
    seedTagRow(spId(1), subCat(101), 9, 4, null); // stale — never stamped
    seedTagRow(spId(1), subCat(102), 7, 6, "run-1"); // fresh — this run already touched it

    int swept = dao.resetRowsNotTouchedByRun("run-1");

    assertThat(swept).isEqualTo(2);
    Row untouchedByOldRun = readTagRow(1, 100);
    assertThat(untouchedByOldRun.firstJobUsersCount).isZero();
    assertThat(untouchedByOldRun.rebooked90dUsersCount).isZero();
    assertThat(untouchedByOldRun.lastRunId).isEqualTo("run-1");

    Row stillFresh = readTagRow(1, 102);
    assertThat(stillFresh.firstJobUsersCount).isEqualTo(7); // untouched by the sweep
  }

  @Test
  void insertDeltaLedgerRowsWritesZeroCumulativeAndNullRateRankTier() {
    dao.insertDeltaLedgerRows(
        "run-1", SebalTaggingRunMode.INCREMENTAL, List.of(new SubCategoryRebookingDelta(1, 100, 3, 1)));

    jdbcTemplate.query(
        "select * from " + LEDGER_TABLE + " where sp_id = 1 and sub_category_id = 100",
        rs -> {
          assertThat(rs.getString("row_type")).isEqualTo("DELTA");
          assertThat(rs.getLong("delta_first_job_users_count")).isEqualTo(3);
          assertThat(rs.getLong("cumulative_first_job_users_count")).isZero();
          rs.getBigDecimal("rebooking_rate"); // must not throw; asserting null below
          assertThat(rs.getObject("rebooking_rate")).isNull();
        });
  }

  @Test
  void applyRanksStampsRateRankAndTierOntoTheAggregateRow() {
    long id = seedTagRow(spId(1), subCat(100), 5, 2, "run-1");

    dao.applyRanks(List.of(rankedRow(id, 1, 100, 5, 2, new BigDecimal("40.00"), 1, "EXCELLENT")));

    Row row = readTagRow(1, 100);
    assertThat(row.rebookingRate).isEqualByComparingTo("40.00");
    assertThat(row.nationalRank).isEqualTo(1);
    assertThat(row.tier).isEqualTo("EXCELLENT");
    // applyRanks must not touch the counts it didn't come to change.
    assertThat(row.firstJobUsersCount).isEqualTo(5);
    assertThat(row.rebooked90dUsersCount).isEqualTo(2);
  }

  // --- fixture helpers -----------------------------------------------------------------------

  private static int spId(int v) {
    return v;
  }

  private static int subCat(int v) {
    return v;
  }

  private long seedTagRow(
      int spId, int subCategoryId, long firstJobUsersCount, long rebooked90dUsersCount, String lastRunId) {
    jdbcTemplate.update(
        "insert into "
            + TAGS_TABLE
            + " (sp_id, sub_category_id, first_job_users_count, rebooked_90d_users_count,"
            + " last_run_id, created_at, updated_at) values (?, ?, ?, ?, ?, now(), now())",
        spId,
        subCategoryId,
        firstJobUsersCount,
        rebooked90dUsersCount,
        lastRunId);
    return jdbcTemplate.queryForObject(
        "select id from " + TAGS_TABLE + " where sp_id = ? and sub_category_id = ?",
        Long.class,
        spId,
        subCategoryId);
  }

  private Row readTagRow(int spId, int subCategoryId) {
    return jdbcTemplate.queryForObject(
        "select * from " + TAGS_TABLE + " where sp_id = ? and sub_category_id = ?",
        (rs, rowNum) -> {
          Row r = new Row();
          r.firstJobUsersCount = rs.getLong("first_job_users_count");
          r.rebooked90dUsersCount = rs.getLong("rebooked_90d_users_count");
          r.lastRunId = rs.getString("last_run_id");
          r.rebookingRate = rs.getBigDecimal("rebooking_rate");
          r.nationalRank = (Integer) rs.getObject("national_rank");
          r.tier = rs.getString("tier");
          return r;
        },
        spId,
        subCategoryId);
  }

  private static RankedTagProjection rankedRow(
      long id,
      int spId,
      int subCategoryId,
      long firstJobUsersCount,
      long rebooked90dUsersCount,
      BigDecimal rebookingRate,
      int nationalRank,
      String tier) {
    return new RankedTagProjection() {
      public Long getId() {
        return id;
      }

      public Integer getSpId() {
        return spId;
      }

      public Integer getSubCategoryId() {
        return subCategoryId;
      }

      public Long getFirstJobUsersCount() {
        return firstJobUsersCount;
      }

      public Long getRebooked90dUsersCount() {
        return rebooked90dUsersCount;
      }

      public BigDecimal getRebookingRate() {
        return rebookingRate;
      }

      public Integer getNationalRank() {
        return nationalRank;
      }

      public String getTier() {
        return tier;
      }
    };
  }

  /** Plain read-model for assertions — not the entity, deliberately. */
  private static class Row {
    long firstJobUsersCount;
    long rebooked90dUsersCount;
    String lastRunId;
    BigDecimal rebookingRate;
    Integer nationalRank;
    String tier;
  }
}
