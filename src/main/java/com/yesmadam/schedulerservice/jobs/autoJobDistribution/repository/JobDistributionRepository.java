package com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistribution;

/**
 * Round lifecycle reads and closes for {@code tbl_job_distribution}, ported from cron's {@code
 * JobDistributionDaoImpl} (SAAB-349).
 *
 * <p><b>Every close sets {@code is_active = NULL}, never 0.</b> The {@code UNIQUE KEY (order_id,
 * is_active)} is what enforces one live round per order, and MySQL only treats NULL as distinct — a
 * 0 here would make the unique key reject the order's next round outright.
 */
@Repository
public interface JobDistributionRepository extends JpaRepository<JobDistribution, Long> {

  /**
   * All live rounds for the advance loop to evaluate.
   *
   * <p><b>Access path:</b> {@code idx_active_status (is_active, status)} — equality on both
   * columns. Bounded to ~1 row by the one-active-distribution invariant, which is the whole point
   * of the fire gate; if this ever returns many rows, the invariant has already been broken
   * upstream.
   */
  @Query("SELECT d FROM JobDistribution d WHERE d.isActive = 1 AND d.status = 'DISTRIBUTED'")
  List<JobDistribution> findActiveDistributed();

  /**
   * Order ids that already have a live round, or a FINAL_UNASSIGNED round created today — the
   * re-trigger guard. Historical rows never change after creation, so the replica is safe here.
   *
   * <p><b>Access path:</b> {@code idx_order_created (order_id, created_at)} — range on the leading
   * {@code order_id IN (...)} with {@code created_at} filtered from the same index entry. Bounded
   * by the caller's id list, which is at most one distribution batch (200).
   */
  @Query(
      "SELECT DISTINCT d.orderId FROM JobDistribution d WHERE d.orderId IN :orderIds"
          + " AND d.createdAt >= :startOfDay"
          + " AND (d.isActive = 1 OR d.status = 'FINAL_UNASSIGNED')")
  List<Long> findBlockedOrderIds(
      @Param("orderIds") Collection<Long> orderIds, @Param("startOfDay") Timestamp startOfDay);

  /**
   * SP-notified counts for every round this order has had, most recent first. Feeds the
   * consecutive-zero-round streak that decides FINAL_UNASSIGNED.
   *
   * <p><b>Access path:</b> {@code idx_order_created (order_id, created_at)} for the {@code
   * order_id} equality. The {@code ORDER BY round_number DESC} is not covered by that index, so
   * MySQL sorts the matched rows — acceptable because rounds-per-order stays in single digits,
   * capped by the consecutive-dry rule. One call per live round, so ~1 per pass.
   */
  @Query(
      "SELECT d.spNotifiedCount FROM JobDistribution d WHERE d.orderId = :orderId"
          + " ORDER BY d.roundNumber DESC")
  List<Integer> findNotifiedCountsDesc(@Param("orderId") Long orderId);

  /** Closes a round as ACCEPTED, recording who accepted and for how much. */
  @Modifying
  @Query(
      "UPDATE JobDistribution d SET d.status = 'ACCEPTED', d.isActive = NULL,"
          + " d.acceptedSpId = :spId, d.acceptedIncentive = :incentive, d.updatedAt = :now"
          + " WHERE d.id = :id")
  int closeAsAccepted(
      @Param("id") Long id,
      @Param("spId") Integer spId,
      @Param("incentive") Integer incentive,
      @Param("now") Timestamp now);

  /**
   * Closes a round with a terminal status and no next round. {@code status} is supplied by the
   * caller from {@link
   * com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionStatus}, so the
   * seven close paths share one statement instead of seven near-identical ones.
   */
  @Modifying
  @Query(
      "UPDATE JobDistribution d SET d.status = :status, d.isActive = NULL, d.updatedAt = :now"
          + " WHERE d.id = :id")
  int closeWithStatus(
      @Param("id") Long id, @Param("status") String status, @Param("now") Timestamp now);
}
