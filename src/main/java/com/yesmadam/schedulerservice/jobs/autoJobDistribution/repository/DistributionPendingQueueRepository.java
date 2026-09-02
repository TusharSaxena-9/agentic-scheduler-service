package com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.DistributionPendingQueue;

/**
 * The waiting pool, ported from cron's pending-queue DAO methods (SAAB-349).
 *
 * <p>Note the id-scoped vs order-scoped split, which is not arbitrary. {@link #clearPending} and
 * {@link #clearPendingAsDistributed} take a row id because a re-queued order legitimately has
 * several historical rows and only the claimed one may be touched. {@link #markPendingResolved} is
 * order-scoped because the advance loop knows only the order id at that point — its {@code
 * isPending IS NULL} predicate is what narrows it to the single active row.
 */
@Repository
public interface DistributionPendingQueueRepository
    extends JpaRepository<DistributionPendingQueue, Long> {

  /**
   * Everything currently waiting.
   *
   * <p>Served by the <b>writer</b>: the trigger side enqueues and then immediately selects, so a
   * lagging replica could miss a just-enqueued row. The caller must not run this read-only.
   *
   * <p><b>Access path:</b> {@code idx_is_pending (is_pending)} — equality on {@code is_pending =
   * 1}. The waiting set stays small by construction: one order fires per gate opening, so the queue
   * drains rather than accumulating, and the table held 34 rows on UAT.
   */
  @Query("SELECT q FROM DistributionPendingQueue q WHERE q.isPending = 1")
  List<DistributionPendingQueue> findPending();

  /**
   * Discards a stale waiting row that never became active ({@code 1} to {@code 0}).
   *
   * <p><b>Access path:</b> {@code PRIMARY}.
   */
  @Modifying
  @Query(
      "UPDATE DistributionPendingQueue q SET q.isPending = 0, q.updatedAt = :now"
          + " WHERE q.id = :id AND q.isPending = 1")
  int clearPending(@Param("id") Long id, @Param("now") Timestamp now);

  /**
   * Marks a waiting row as fired ({@code 1} to {@code NULL}), stamping {@code distributed_at}. Only
   * called after a confirmed successful fire.
   *
   * <p><b>Access path:</b> {@code PRIMARY}.
   */
  @Modifying
  @Query(
      "UPDATE DistributionPendingQueue q SET q.isPending = NULL, q.distributedAt = :now,"
          + " q.updatedAt = :now WHERE q.id = :id AND q.isPending = 1")
  int clearPendingAsDistributed(@Param("id") Long id, @Param("now") Timestamp now);

  /**
   * Flips the order's active row to resolved ({@code NULL} to {@code 0}) once its round is
   * terminal.
   *
   * <p><b>Access path: none — a scan.</b> The predicate is {@code order_id = ? AND is_pending IS
   * NULL} and no index leads on {@code order_id} here; {@code idx_is_pending} cannot serve an
   * {@code IS NULL} usefully. Acceptable while the table stays in the tens of rows, which the
   * drain-one-at- a-time design maintains. If it ever grows, the remedy is:
   *
   * <pre>ALTER TABLE ysmdm_users.tbl_distribution_pending_queue
   *   ADD INDEX idx_pending_queue_order_id (order_id);</pre>
   */
  @Modifying
  @Query(
      "UPDATE DistributionPendingQueue q SET q.isPending = 0, q.updatedAt = :now"
          + " WHERE q.orderId = :orderId AND q.isPending IS NULL")
  int markPendingResolved(@Param("orderId") Long orderId, @Param("now") Timestamp now);
}
