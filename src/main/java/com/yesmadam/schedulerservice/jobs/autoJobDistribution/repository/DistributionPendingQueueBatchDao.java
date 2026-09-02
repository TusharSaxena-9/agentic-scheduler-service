package com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.common.constants.DBConstants;

/**
 * The enqueue half of the waiting pool, ported from cron's {@code
 * JobDistributionDaoImpl#enqueuePending} (SAAB-349).
 *
 * <p>Separate from {@link DistributionPendingQueueRepository} because there is no JPA way to
 * express {@code INSERT IGNORE}, and the insert has to be one, not one per order: the trigger side
 * hands over up to {@code BATCH_CHUNK_SIZE} orders at a time.
 *
 * <p><b>{@code INSERT IGNORE} is the concurrency control, not an optimisation.</b> It leans on
 * {@code uq_pending_order_active} to make a re-enqueue a no-op, which is what lets this be called
 * again safely — by a Temporal activity retry, by the next scheduled tick, or by a second worker
 * pod. Without that unique key the statement silently degrades to inserting a duplicate waiting row
 * every time, and the same order would be fired twice.
 *
 * <p>Written as a literal multi-row {@code VALUES} list rather than {@code
 * PreparedStatement.addBatch()} — following {@code SebalTaggingBatchDao} — so it is a single round
 * trip without depending on {@code rewriteBatchedStatements=true} being present in the JDBC URL of
 * every environment.
 */
@Repository
public class DistributionPendingQueueBatchDao {

  private static final String PENDING_QUEUE_TABLE =
      DBConstants.YESMADAM_USERS + ".tbl_distribution_pending_queue";

  /**
   * Orders per statement. Well inside both {@code max_allowed_packet} and the 65,535-placeholder
   * protocol limit at one placeholder per row, and comfortably above {@code BATCH_CHUNK_SIZE} so a
   * normal batch is always a single statement.
   */
  private static final int ROWS_PER_STATEMENT = 500;

  private final JdbcTemplate jdbcTemplate;

  public DistributionPendingQueueBatchDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Adds orders to the waiting pool as {@code is_pending = 1}, skipping any that already have an
   * active row.
   *
   * @param orderIds the orders to enqueue; empty or null is a no-op
   * @return the number of rows actually inserted — less than {@code orderIds.size()} whenever an
   *     order was already waiting
   */
  public int enqueuePending(List<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return 0;
    }
    int inserted = 0;
    for (int from = 0; from < orderIds.size(); from += ROWS_PER_STATEMENT) {
      List<Long> chunk =
          orderIds.subList(from, Math.min(from + ROWS_PER_STATEMENT, orderIds.size()));
      inserted += insertChunk(chunk);
    }
    return inserted;
  }

  private int insertChunk(List<Long> orderIds) {
    StringBuilder sql =
        new StringBuilder("INSERT IGNORE INTO ")
            .append(PENDING_QUEUE_TABLE)
            .append(" (order_id, is_pending, created_at, updated_at) VALUES ");
    for (int i = 0; i < orderIds.size(); i++) {
      sql.append(i == 0 ? "" : ", ").append("(?, 1, NOW(), NOW())");
    }
    return jdbcTemplate.update(sql.toString(), orderIds.toArray());
  }
}
