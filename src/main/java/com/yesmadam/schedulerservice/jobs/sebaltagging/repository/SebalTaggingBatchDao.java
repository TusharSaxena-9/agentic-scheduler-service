package com.yesmadam.schedulerservice.jobs.sebaltagging.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.google.common.collect.Lists;
import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingLedgerRowType;
import com.yesmadam.schedulerservice.jobs.sebaltagging.enums.SebalTaggingRunMode;
import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SubCategoryRebookingDelta;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.RankedTagProjection;
import com.yesmadam.utilities.DBConstants;

/**
 * Bulk writes for the SEBAL tagging tables as multi-row native statements, rather than
 * entity-at-a-time {@code saveAll}.
 *
 * <p>This is not a "JDBC is faster than JPA" micro-optimization — the per-call overhead difference
 * between the two is microseconds and irrelevant here. It is purely about statement count, and JPA
 * structurally cannot help:
 *
 * <ul>
 *   <li>{@code saveAll} is a loop over {@code save} — one statement per entity, by design.
 *   <li>Both tagging entities use {@code GenerationType.IDENTITY}, so Hibernate <em>must</em>
 *       execute each INSERT at {@code save()} time to read back the generated key. It cannot defer
 *       to flush, so it cannot batch: {@code hibernate.jdbc.batch_size} is structurally unusable
 *       for them, not merely unset.
 *   <li>There is no JPA way to express {@code ON DUPLICATE KEY UPDATE c = c + VALUES(c)}, so the
 *       entity path also needs a read-before-write to merge counts.
 * </ul>
 *
 * <p>Measured on the 2026-07-24 FULL run: the old path spent ~32 ms per statement and issued two
 * statements per (SP, sub-category) pair, so a ~3300-pair chunk took ~215 s of pure round trips
 * against ~6.5 s of actual query time. Collapsing that to a handful of multi-row statements also
 * shortens the write transaction by the same factor — these batches hold their row locks for
 * roughly a second instead of the several minutes the entity path held them for, so this reduces
 * lock contention rather than adding it.
 *
 * <p>Statements are built as literal multi-row {@code VALUES} lists, so this does not depend on the
 * JDBC URL carrying {@code rewriteBatchedStatements=true} (which only affects
 * {@code PreparedStatement.addBatch}).
 */
@Repository
public class SebalTaggingBatchDao {

  /**
   * Rows per statement. Sized so the widest statement here (the 14-column summary ledger insert)
   * stays well inside both {@code max_allowed_packet} and the 65,535-placeholder limit of the
   * MySQL protocol, while still being large enough that per-statement round-trip cost stops
   * mattering.
   */
  private static final int TUPLES_PER_STATEMENT = 500;

  private static final String TAGS_TABLE =
      DBConstants.YESMADAM_ADMIN + ".tbl_sp_sebal_subcategory_tags";
  private static final String LEDGER_TABLE =
      DBConstants.YESMADAM_ADMIN + ".tbl_sp_sebal_subcategory_tag_ledgers";

  private final JdbcTemplate jdbcTemplate;

  public SebalTaggingBatchDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Folds one chunk's deltas onto the running aggregate, creating the {@code (sp, sub-category)}
   * row if this is the first time the pair has been seen.
   *
   * <p>The merge happens inside MySQL via the existing {@code (sp_id, sub_category_id)} unique
   * constraint, which is what lets the previous {@code findBySp_IdIn} read-before-write disappear
   * entirely — the counts are incremented in place rather than read, added in Java, and written
   * back.
   *
   * <p>How the counts combine depends on the run mode, and this is what makes the two modes
   * actually mean different things:
   *
   * <ul>
   *   <li><b>INCREMENTAL</b> always adds. The chunk's delta is a contribution to a historical
   *       total this run never recomputed, so adding is the only correct operation.
   *   <li><b>FULL</b> replaces on the run's first touch of a pair, then adds for the rest of that
   *       run — see {@code SpSebalSubCategoryTag.lastRunId}. That gives FULL its "rebuild from
   *       source, previous state irrelevant" semantics without a destructive up-front reset.
   * </ul>
   *
   * <p>Note the assignment order in the FULL branch: {@code last_run_id} is written <em>after</em>
   * both counts. MySQL evaluates {@code ON DUPLICATE KEY UPDATE} assignments left to right and
   * later ones see earlier updates, so stamping the run id first would make every comparison
   * match and silently turn every replace into an add.
   *
   * <p>Batches are ordered by {@code (sp_id, sub_category_id)} so concurrent writers always take
   * row locks in the same sequence. The chunk loop is sequential today, but this keeps the
   * statement safe if chunks are ever fanned out.
   */
  public void upsertTagDeltas(
      List<SubCategoryRebookingDelta> deltas, SebalTaggingRunMode runMode, String runId) {
    if (deltas.isEmpty()) {
      return;
    }

    List<SubCategoryRebookingDelta> ordered = new ArrayList<>(deltas);
    ordered.sort(
        Comparator.comparing(SubCategoryRebookingDelta::getSpId)
            .thenComparing(SubCategoryRebookingDelta::getSubCategoryId));

    // "Has this run already written this row?" — null last_run_id (a row predating this column,
    // or written by an older run) is not equal to the current run id, so it takes the replace
    // branch, which is the correct reading.
    String writtenByThisRun = "last_run_id <=> values(last_run_id)";
    String countUpdates =
        runMode == SebalTaggingRunMode.FULL
            ? " first_job_users_count = if("
                + writtenByThisRun
                + ", first_job_users_count + values(first_job_users_count),"
                + " values(first_job_users_count)),"
                + " rebooked_90d_users_count = if("
                + writtenByThisRun
                + ", rebooked_90d_users_count + values(rebooked_90d_users_count),"
                + " values(rebooked_90d_users_count)),"
            : " first_job_users_count = first_job_users_count + values(first_job_users_count),"
                + " rebooked_90d_users_count ="
                + " rebooked_90d_users_count + values(rebooked_90d_users_count),";

    Timestamp now = Timestamp.from(Instant.now());

    for (List<SubCategoryRebookingDelta> batch : Lists.partition(ordered, TUPLES_PER_STATEMENT)) {
      String sql =
          "insert into "
              + TAGS_TABLE
              + " (sp_id, sub_category_id, first_job_users_count, rebooked_90d_users_count,"
              + " last_run_id, created_at, updated_at) values "
              + valuesClause(batch.size(), 7)
              + " on duplicate key update"
              + countUpdates
              + " last_run_id = values(last_run_id),"
              + " updated_at = values(updated_at)";

      List<Object> args = new ArrayList<>(batch.size() * 7);
      for (SubCategoryRebookingDelta delta : batch) {
        args.add(delta.getSpId());
        args.add(delta.getSubCategoryId());
        args.add(delta.getFirstJobUsersCount());
        args.add(delta.getRebooked90dUsersCount());
        args.add(runId);
        args.add(now);
        args.add(now);
      }
      jdbcTemplate.update(sql, args.toArray());
    }
  }

  /**
   * Zeroes one batch of rows that this FULL run never touched, and stamps them with its run id.
   *
   * <p>Closes the last gap in "FULL means rebuild": a pair that existed before the run but has no
   * qualifying orders any more is never visited by any chunk, so replace-on-first-touch alone
   * would leave it sitting there with stale counts and a stale tier forever.
   *
   * <p>Deliberately batched with {@code LIMIT} rather than run as one table-wide {@code UPDATE} —
   * that would be exactly the long-held write lock over everything this job was restructured to
   * avoid. Stamping {@code last_run_id} as part of the same statement is what makes the loop
   * terminate: swept rows stop matching the predicate.
   *
   * @return rows swept; callers loop until this returns 0
   */
  public int resetRowsNotTouchedByRun(String runId) {
    String sql =
        "update "
            + TAGS_TABLE
            + " set first_job_users_count = 0, rebooked_90d_users_count = 0,"
            + " rebooking_rate = null, national_rank = null, tier = null,"
            + " last_run_id = ?, updated_at = ?"
            + " where last_run_id is null or last_run_id <> ?"
            + " limit "
            + TUPLES_PER_STATEMENT;
    return jdbcTemplate.update(sql, runId, Timestamp.from(Instant.now()), runId);
  }

  /**
   * Appends this chunk's {@code DELTA} ledger rows. Cumulative counts are written as zero and
   * rate/rank/tier left null — they aren't knowable until every chunk has merged and ranking has
   * run, and these rows are never revisited to fill them in (see
   * {@link #insertSummaryLedgerRows}).
   */
  public void insertDeltaLedgerRows(
      String runId, SebalTaggingRunMode runMode, List<SubCategoryRebookingDelta> deltas) {
    if (deltas.isEmpty()) {
      return;
    }

    Timestamp now = Timestamp.from(Instant.now());

    for (List<SubCategoryRebookingDelta> batch : Lists.partition(deltas, TUPLES_PER_STATEMENT)) {
      String sql =
          "insert into "
              + LEDGER_TABLE
              + " (run_id, run_date, run_mode, row_type, sp_id, sub_category_id,"
              + " delta_first_job_users_count, delta_rebooked_90d_users_count,"
              + " cumulative_first_job_users_count, cumulative_rebooked_90d_users_count,"
              + " created_at) values "
              + valuesClause(batch.size(), 11);

      List<Object> args = new ArrayList<>(batch.size() * 11);
      for (SubCategoryRebookingDelta delta : batch) {
        args.add(runId);
        args.add(now);
        args.add(runMode.name());
        args.add(SebalTaggingLedgerRowType.DELTA.name());
        args.add(delta.getSpId());
        args.add(delta.getSubCategoryId());
        args.add(delta.getFirstJobUsersCount());
        args.add(delta.getRebooked90dUsersCount());
        args.add(0L);
        args.add(0L);
        args.add(now);
      }
      jdbcTemplate.update(sql, args.toArray());
    }
  }

  /**
   * Appends one {@code SUMMARY} ledger row per ranked pair — the run's final cumulative
   * counts/rate/rank/tier, as new rows. Never touches the {@code DELTA} rows the same run's chunks
   * already wrote; {@code delta*} is zero here since a summary row isn't itself a contribution.
   */
  public void insertSummaryLedgerRows(
      String runId, SebalTaggingRunMode runMode, List<RankedTagProjection> ranked) {
    if (ranked.isEmpty()) {
      return;
    }

    Timestamp now = Timestamp.from(Instant.now());

    for (List<RankedTagProjection> batch : Lists.partition(ranked, TUPLES_PER_STATEMENT)) {
      String sql =
          "insert into "
              + LEDGER_TABLE
              + " (run_id, run_date, run_mode, row_type, sp_id, sub_category_id,"
              + " delta_first_job_users_count, delta_rebooked_90d_users_count,"
              + " cumulative_first_job_users_count, cumulative_rebooked_90d_users_count,"
              + " rebooking_rate, national_rank, tier, created_at) values "
              + valuesClause(batch.size(), 14);

      List<Object> args = new ArrayList<>(batch.size() * 14);
      for (RankedTagProjection row : batch) {
        args.add(runId);
        args.add(now);
        args.add(runMode.name());
        args.add(SebalTaggingLedgerRowType.SUMMARY.name());
        args.add(row.getSpId());
        args.add(row.getSubCategoryId());
        args.add(0L);
        args.add(0L);
        args.add(row.getFirstJobUsersCount());
        args.add(row.getRebooked90dUsersCount());
        args.add(row.getRebookingRate());
        args.add(row.getNationalRank());
        args.add(row.getTier());
        args.add(now);
      }
      jdbcTemplate.update(sql, args.toArray());
    }
  }

  /**
   * Stamps freshly-computed rate/rank/tier onto the aggregate rows they were computed from.
   *
   * <p>Written as an upsert keyed on the primary key rather than N single-row {@code UPDATE}s
   * because the MySQL driver cannot rewrite {@code UPDATE}s into one multi-row statement, so a
   * batched update would still cost one round trip per row — the exact problem being fixed here.
   * Every row supplied came from a {@code SELECT} on this table moments earlier, so in practice
   * this always takes the update branch; the insert branch only fires if a row was deleted in
   * between, in which case restoring it with its own computed values is the harmless outcome.
   *
   * <p>Ordered by id so batches take row locks in a consistent sequence.
   */
  public void applyRanks(List<RankedTagProjection> ranked) {
    if (ranked.isEmpty()) {
      return;
    }

    List<RankedTagProjection> ordered = new ArrayList<>(ranked);
    ordered.sort(Comparator.comparing(RankedTagProjection::getId));

    Timestamp now = Timestamp.from(Instant.now());

    for (List<RankedTagProjection> batch : Lists.partition(ordered, TUPLES_PER_STATEMENT)) {
      String sql =
          "insert into "
              + TAGS_TABLE
              + " (id, sp_id, sub_category_id, first_job_users_count, rebooked_90d_users_count,"
              + " rebooking_rate, national_rank, tier, created_at, updated_at) values "
              + valuesClause(batch.size(), 10)
              + " on duplicate key update"
              + " rebooking_rate = values(rebooking_rate),"
              + " national_rank = values(national_rank),"
              + " tier = values(tier),"
              + " updated_at = values(updated_at)";

      List<Object> args = new ArrayList<>(batch.size() * 10);
      for (RankedTagProjection row : batch) {
        args.add(row.getId());
        args.add(row.getSpId());
        args.add(row.getSubCategoryId());
        args.add(row.getFirstJobUsersCount());
        args.add(row.getRebooked90dUsersCount());
        args.add(row.getRebookingRate());
        args.add(row.getNationalRank());
        args.add(row.getTier());
        args.add(now);
        args.add(now);
      }
      jdbcTemplate.update(sql, args.toArray());
    }
  }

  /** Builds {@code (?, ?, ...), (?, ?, ...), ...} for a multi-row {@code VALUES} list. */
  private static String valuesClause(int rows, int columns) {
    String tuple = "(" + "?, ".repeat(columns - 1) + "?)";
    return String.join(", ", Collections.nCopies(rows, tuple));
  }
}
