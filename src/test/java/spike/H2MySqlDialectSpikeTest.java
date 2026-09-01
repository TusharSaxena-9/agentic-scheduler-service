package spike;

// SPIKE TEST — not part of the real suite. Run with:
//   mvn -Dtest=spike.H2MySqlDialectSpikeTest test
//
// RESULT of all runs against real H2 2.x in MySQL mode -- FOUR confirmed, real divergences
// from MySQL, each fixed in production code, not worked around here:
//   1. Integer division TRUNCATES in H2 (int/int -> int), unlike MySQL which promotes it to a
//      4-decimal-scale result. FIXED: SpSebalSubCategoryTagRepository now casts explicitly
//      (cast(... as decimal(20,4))) so both engines agree.
//   2. H2 does NOT support the '<=>' null-safe-equal operator AT ALL, in any compatibility mode.
//   3. H2 does NOT recognize MySQL's IF(cond, a, b) function in this position (it has its own
//      CASEWHEN() instead, and reserves the bare word IF for other constructs).
//   4. H2's VALUES(col) emulation inside ON DUPLICATE KEY UPDATE only understands VALUES(col)
//      as the bare right-hand side of a top-level "column = VALUES(column)" assignment --
//      nested inside a CASE WHEN condition (or any other boolean expression), it mis-parses
//      as an unrelated VALUES(...) row-constructor clause.
//   FIXED (2, 3, 4 together): SebalTaggingBatchDao now compares last_run_id directly against
//   the runId method parameter (bound as a plain "?"), using CASE WHEN instead of IF(...), and
//   never nests VALUES(...) inside a condition. Since runId is never null, no null-safe
//   operator is needed either -- a null stored last_run_id already compares as not-equal
//   under ordinary SQL null semantics.
// All tests below must pass before trusting the real integration tests in
// SebalTaggingBatchDaoH2IntegrationTest / SpSebalSubCategoryTagRepositoryH2IntegrationTest.

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class H2MySqlDialectSpikeTest {

  // Fresh DB name per test: DB_CLOSE_DELAY=-1 keeps a named in-memory DB alive across connection
  // close, so a fixed name leaks tables between tests in the same JVM.
  private static final AtomicInteger DB_COUNTER = new AtomicInteger();

  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    String dbName = "spike_" + DB_COUNTER.incrementAndGet();
    conn =
        DriverManager.getConnection(
            "jdbc:h2:mem:"
                + dbName
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,KEY,END,START");
  }

  @AfterEach
  void tearDown() throws Exception {
    conn.close();
  }

  @Test
  void castingToDecimalMakesIntegerDivisionMatchMySql() throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute("create table t (rebooked_90d_users_count int, first_job_users_count int)");
      st.execute("insert into t values (3, 7)"); // real MySQL: 3/7 = 0.4286
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select rebooked_90d_users_count / cast(first_job_users_count as"
                    + " decimal(20,4)) as rate, round(rebooked_90d_users_count /"
                    + " cast(first_job_users_count as decimal(20,4)) * 100, 2) as rate_pct"
                    + " from t")) {
      assertTrue(rs.next());
      BigDecimal rate = rs.getBigDecimal("rate");
      BigDecimal ratePct = rs.getBigDecimal("rate_pct");
      System.out.println("=== DIVISION RESULT (with cast) === rate=" + rate + " rate_pct=" + ratePct);
      assertTrue(
          rate.compareTo(BigDecimal.ZERO) > 0 && rate.compareTo(BigDecimal.ONE) < 0,
          "Expected a fractional rate strictly between 0 and 1. Got: " + rate);
      assertEquals(0, ratePct.compareTo(new BigDecimal("42.86")));
    }
  }

  @Test
  void withoutTheCastH2TruncatesIntegerDivisionUnlikeMySql() throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute("create table t (rebooked_90d_users_count int, first_job_users_count int)");
      st.execute("insert into t values (3, 7)");
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select rebooked_90d_users_count / first_job_users_count as rate from t")) {
      assertTrue(rs.next());
      BigDecimal rate = rs.getBigDecimal("rate");
      System.out.println("=== DIVISION RESULT (no cast, confirms the bug) === rate=" + rate);
      assertEquals(
          0,
          rate.compareTo(BigDecimal.ZERO),
          "If this ever stops being 0, H2's integer-division behavior changed and the cast"
              + " fix may no longer be necessary (but shouldn't be removed without re-checking"
              + " against a real MySQL 8 instance).");
    }
  }

  @Test
  void h2DoesNotSupportTheNullSafeEqualOperatorAtAll() throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute("create table t (a int, b int)");
    }
    try (Statement st = conn.createStatement()) {
      Exception ex =
          assertThrows(Exception.class, () -> st.executeQuery("select 1 from t where a <=> b"));
      System.out.println("=== <=> CONFIRMED UNSUPPORTED === " + ex.getMessage());
    }
  }

  @Test
  void h2MisparsesValuesFunctionWhenNestedInsideACaseWhenCondition() throws Exception {
    // Documents the actual bug the runId-bind-parameter fix avoids -- kept as a regression
    // guard so nobody "simplifies" the production SQL back to comparing against
    // values(last_run_id) inside the CASE WHEN condition.
    try (Statement st = conn.createStatement()) {
      st.execute(
          "create table t (sp_id int, cnt int, last_run_id varchar(50), primary key (sp_id))");
      st.execute("insert into t values (1, 5, 'run-1')");
    }
    try (Statement st = conn.createStatement()) {
      Exception ex =
          assertThrows(
              Exception.class,
              () ->
                  st.execute(
                      "insert into t (sp_id, cnt, last_run_id) values (1, 3, 'run-2') on"
                          + " duplicate key update cnt = case when (last_run_id ="
                          + " values(last_run_id) or (last_run_id is null and"
                          + " values(last_run_id) is null)) then cnt + values(cnt) else"
                          + " values(cnt) end, last_run_id = values(last_run_id)"));
      System.out.println("=== NESTED VALUES() IN CASE WHEN CONFIRMED BROKEN === " + ex.getMessage());
    }
  }

  @Test
  void onDuplicateKeyUpdateReplacesWhenStoredRunIdDiffersFromTheBoundRunId() throws Exception {
    // Mirrors SebalTaggingBatchDao.upsertTagDeltas's FULL-mode branch exactly, post-fix:
    // compares last_run_id directly against a bound "?" (the runId parameter), not against
    // values(last_run_id) -- and needs no null-safe operator since the bound value is never
    // null.
    try (Statement st = conn.createStatement()) {
      st.execute(
          "create table t (sp_id int, sub_cat_id int, cnt int, last_run_id varchar(50),"
              + " primary key (sp_id, sub_cat_id))");
      st.execute("insert into t values (1, 100, 5, 'run-1')");
    }
    String sql =
        "insert into t (sp_id, sub_cat_id, cnt, last_run_id) values (1, 100, 3, ?)"
            + " on duplicate key update"
            + " cnt = case when last_run_id = ? then cnt + values(cnt) else values(cnt) end,"
            + " last_run_id = values(last_run_id)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, "run-2"); // the row's own last_run_id being inserted
      ps.setString(2, "run-2"); // the CASE WHEN's bound comparison value -- same runId
      ps.executeUpdate();
    }
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select cnt, last_run_id from t where sp_id = 1")) {
      assertTrue(rs.next());
      int cnt = rs.getInt("cnt");
      String runId = rs.getString("last_run_id");
      System.out.println("=== REPLACE BRANCH === cnt=" + cnt + " last_run_id=" + runId);
      // Stored 'run-1' != bound 'run-2' -> replace branch -> cnt = 3, not 5 + 3.
      assertEquals(3, cnt, "Expected replace-branch (different run id) to win: cnt = 3");
      assertEquals("run-2", runId);
    }
  }

  @Test
  void onDuplicateKeyUpdateAddsWhenStoredRunIdMatchesTheBoundRunId() throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute(
          "create table t (sp_id int, sub_cat_id int, cnt int, last_run_id varchar(50),"
              + " primary key (sp_id, sub_cat_id))");
      st.execute("insert into t values (1, 100, 5, 'run-1')");
    }
    String sql =
        "insert into t (sp_id, sub_cat_id, cnt, last_run_id) values (1, 100, 3, ?)"
            + " on duplicate key update"
            + " cnt = case when last_run_id = ? then cnt + values(cnt) else values(cnt) end,"
            + " last_run_id = values(last_run_id)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, "run-1"); // same run id as already stored
      ps.setString(2, "run-1");
      ps.executeUpdate();
    }
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select cnt from t where sp_id = 1")) {
      assertTrue(rs.next());
      assertEquals(8, rs.getInt("cnt"), "Same run id must add: 5 + 3 = 8");
    }
  }

  @Test
  void onDuplicateKeyUpdateReplacesWhenStoredRunIdIsNull() throws Exception {
    // The case the old <=> / null-safe-OR expression existed to handle -- confirms plain "="
    // against a non-null bound value already does the right thing without it.
    try (Statement st = conn.createStatement()) {
      st.execute(
          "create table t (sp_id int, sub_cat_id int, cnt int, last_run_id varchar(50),"
              + " primary key (sp_id, sub_cat_id))");
      st.execute("insert into t (sp_id, sub_cat_id, cnt, last_run_id) values (1, 100, 5, null)");
    }
    String sql =
        "insert into t (sp_id, sub_cat_id, cnt, last_run_id) values (1, 100, 3, ?)"
            + " on duplicate key update"
            + " cnt = case when last_run_id = ? then cnt + values(cnt) else values(cnt) end,"
            + " last_run_id = values(last_run_id)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, "run-1");
      ps.setString(2, "run-1");
      ps.executeUpdate();
    }
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select cnt from t where sp_id = 1")) {
      assertTrue(rs.next());
      assertEquals(3, rs.getInt("cnt"), "Null stored last_run_id must take the replace branch");
    }
  }
}
