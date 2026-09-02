package com.yesmadam.schedulerservice.jobs.spNotReachedHome.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.model.ReachedHomeUpdate;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.SpNotReachedHomeCandidateProjection;

/**
 * The tracker's two bulk writes, as literal multi-row native statements — same approach as {@code
 * SebalTaggingBatchDao}, and for the same reason: neither statement is expressible in JPA.
 *
 * <ul>
 *   <li>The insert needs {@code ON DUPLICATE KEY UPDATE} to be idempotent within a day.
 *   <li>The entity uses {@code GenerationType.IDENTITY}, so Hibernate must execute each insert at
 *       {@code save()} time to read the generated key back and structurally cannot batch.
 * </ul>
 *
 * <p>Built as multi-row {@code VALUES} lists rather than {@code PreparedStatement.addBatch()},
 * which is what cron's original used. That matters operationally: {@code addBatch} only collapses
 * into one round trip when the JDBC URL carries {@code rewriteBatchedStatements=true}, a
 * requirement SAAB-90 had to document per environment. A literal multi-row statement is one round
 * trip regardless, so this port drops that deployment dependency.
 */
@Repository
public class SpNotReachedHomeBatchDao {

  /**
   * Rows per statement. The widest statement here has 9 placeholders per row, so 500 rows is ~4,500
   * placeholders — comfortably inside MySQL's 65,535-placeholder protocol limit and {@code
   * max_allowed_packet}.
   */
  private static final int ROWS_PER_STATEMENT = 500;

  private static final String TRACKER_TABLE =
      DBConstants.YESMADAM_ADMIN + ".tbl_sp_not_reached_home";

  private final JdbcTemplate jdbcTemplate;

  public SpNotReachedHomeBatchDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Inserts today's not-home SPs, refreshing position and distance for any already present.
   *
   * <p>Only the three volatile columns are updated on conflict — the snapshot columns ({@code
   * sp_name}, {@code last_order_id}, {@code last_order_end_time}, {@code home_lat/lon}) are left as
   * first written, matching the original. Note this deliberately does <b>not</b> reset {@code
   * reached_home}: an SP already marked home stays home for the day even if a later cycle sees them
   * out again.
   *
   * @param candidates rows from the eligibility query; empty is a no-op
   * @return number of candidate rows submitted
   */
  public int upsertCandidates(List<SpNotReachedHomeCandidateProjection> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return 0;
    }

    // Ordered by sp_id so concurrent writers always take row locks in the same sequence.
    List<SpNotReachedHomeCandidateProjection> ordered =
        candidates.stream()
            .sorted(Comparator.comparing(SpNotReachedHomeCandidateProjection::getSpId))
            .collect(Collectors.toList());

    int submitted = 0;
    for (int from = 0; from < ordered.size(); from += ROWS_PER_STATEMENT) {
      List<SpNotReachedHomeCandidateProjection> chunk =
          ordered.subList(from, Math.min(from + ROWS_PER_STATEMENT, ordered.size()));

      String values =
          chunk.stream()
              .map(c -> "(?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURDATE())")
              .collect(Collectors.joining(", "));

      String sql =
          "INSERT INTO "
              + TRACKER_TABLE
              + " (sp_id, sp_name, last_order_id, last_order_end_time, current_lat, current_lon,"
              + "  home_lat, home_lon, distance_from_home_mtr, reached_home, tracking_date)"
              + " VALUES "
              + values
              + " ON DUPLICATE KEY UPDATE"
              + "   current_lat = VALUES(current_lat),"
              + "   current_lon = VALUES(current_lon),"
              + "   distance_from_home_mtr = VALUES(distance_from_home_mtr)";

      List<Object> args = new ArrayList<>(chunk.size() * 9);
      for (SpNotReachedHomeCandidateProjection c : chunk) {
        args.add(c.getSpId());
        args.add(c.getSpName());
        args.add(c.getLastOrderId());
        args.add(c.getLastOrderEndTime());
        args.add(c.getCurrentLat());
        args.add(c.getCurrentLon());
        args.add(c.getHomeLat());
        args.add(c.getHomeLon());
        args.add(c.getDistanceFromHomeMtr());
      }

      jdbcTemplate.update(sql, args.toArray());
      submitted += chunk.size();
    }
    return submitted;
  }

  /**
   * Flips the given rows to reached-home, stamping the position that satisfied the threshold.
   *
   * <p>{@code distance_from_home_mtr} is zeroed rather than set to the measured distance — that is
   * the original's behaviour, and it is what distinguishes "confirmed home by the system" from a
   * stale distance reading.
   *
   * @param updates rows to mark; empty is a no-op
   * @return number of rows updated
   */
  public int markReachedHome(List<ReachedHomeUpdate> updates) {
    if (updates == null || updates.isEmpty()) {
      return 0;
    }

    List<ReachedHomeUpdate> ordered =
        updates.stream()
            .sorted(Comparator.comparing(ReachedHomeUpdate::getTrackerRowId))
            .collect(Collectors.toList());

    int updated = 0;
    for (int from = 0; from < ordered.size(); from += ROWS_PER_STATEMENT) {
      List<ReachedHomeUpdate> chunk =
          ordered.subList(from, Math.min(from + ROWS_PER_STATEMENT, ordered.size()));

      // One statement per chunk via CASE, so a chunk is a single round trip. Each row keeps its own
      // measured coordinates, which a blanket UPDATE ... WHERE id IN (...) could not express.
      String latCases = chunk.stream().map(u -> "WHEN ? THEN ?").collect(Collectors.joining(" "));
      String lonCases = chunk.stream().map(u -> "WHEN ? THEN ?").collect(Collectors.joining(" "));
      String idPlaceholders = chunk.stream().map(u -> "?").collect(Collectors.joining(", "));

      String sql =
          "UPDATE "
              + TRACKER_TABLE
              + " SET reached_home = 1,"
              + "     current_lat = CASE id "
              + latCases
              + " END,"
              + "     current_lon = CASE id "
              + lonCases
              + " END,"
              + "     distance_from_home_mtr = 0,"
              + "     operated_by = 'SYSTEM',"
              + "     updated_at = NOW()"
              + " WHERE id IN ("
              + idPlaceholders
              + ")";

      List<Object> args = new ArrayList<>(chunk.size() * 5);
      for (ReachedHomeUpdate u : chunk) {
        args.add(u.getTrackerRowId());
        args.add(u.getCurrentLat());
      }
      for (ReachedHomeUpdate u : chunk) {
        args.add(u.getTrackerRowId());
        args.add(u.getCurrentLon());
      }
      for (ReachedHomeUpdate u : chunk) {
        args.add(u.getTrackerRowId());
      }

      updated += jdbcTemplate.update(sql, args.toArray());
    }
    return updated;
  }
}
