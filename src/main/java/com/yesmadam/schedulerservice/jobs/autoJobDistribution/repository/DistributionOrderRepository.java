package com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistribution;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.projections.DistributionCandidateProjection;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.projections.OrderAcceptanceProjection;

/**
 * Order-side reads for Auto Job Distribution, ported from cron's {@code OrderDaoImpl} batch methods
 * (SAAB-349). Native SQL against {@code tbl_order} and {@code tbl_order_history} with explicit
 * camelCase aliases, so nothing depends on projection name translation.
 *
 * <p>Extends {@code JpaRepository<JobDistribution, Long>} only to satisfy Spring Data's need for a
 * managed domain type; none of these queries touch that entity.
 */
@Repository
public interface DistributionOrderRepository extends JpaRepository<JobDistribution, Long> {

  /**
   * The batched acceptance read that drives every branch of the advance loop.
   *
   * <p><b>Must run against the writer.</b> Admin commits the acceptance moments before this
   * executes, so on a lagging replica an already-accepted round reads as still-open and the loop
   * escalates it — notifying more SPs for a job somebody has already taken. The caller therefore
   * does not mark this read-only.
   */
  @Query(
      value =
          "SELECT o.order_id                        AS orderId,"
              + "       CAST(o.isDistributedJobAccepted AS SIGNED) AS accepted,"
              + "       o.s_p_assign_by_user              AS spId,"
              + "       o.distributed_job_incentive_amount AS incentive,"
              + "       o.order_status_code               AS orderStatusCode,"
              + "       o.order_end_time_with_date        AS orderEndTimeWithDate,"
              + "       CAST(o.booking_date AS DATE)       AS bookingDate"
              + "  FROM "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order o"
              + " WHERE o.order_id IN (:orderIds)",
      nativeQuery = true)
  List<OrderAcceptanceProjection> findAcceptanceView(@Param("orderIds") Collection<Long> orderIds);

  /**
   * Everything needed to filter, re-validate and rank an order — used at both choke points: the
   * trigger side before enqueueing, and {@code selectAndFireNext} immediately before firing.
   *
   * <p>The {@code LEFT JOIN} on {@code tbl_hub} replaces the original's per-order {@code
   * findOne(ServiceHub.class, hubId)} lookup. Same predicate, one query instead of N — the loop
   * could otherwise issue 200 point reads per batch. {@code LEFT}, not inner: an order with no hub,
   * or a hub row that has since been deleted, must still be evaluated and treated as non-franchise,
   * exactly as {@code hub != null && TRUE.equals(hub.getIs_franchise())} did.
   */
  @Query(
      value =
          "SELECT o.order_id                          AS orderId,"
              + "       o.s_p_assign_by_user                AS spAssignByUser,"
              + "       o.order_status_code                 AS orderStatusCode,"
              + "       CAST(o.booking_date AS DATE)         AS bookingDate,"
              + "       o.booking_time                      AS bookingTime,"
              + "       o.order_end_time_with_date          AS orderEndTimeWithDate,"
              + "       CAST(o.unassignWithRemoveReassigning AS SIGNED) AS unassignWithRemoveReassigning,"
              + "       o.category_id                       AS categoryId,"
              + "       o.hub_id                            AS hubId,"
              + "       CAST(h.is_franchise AS SIGNED)      AS hubIsFranchise"
              + "  FROM "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order o"
              + "  LEFT JOIN "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_hub h ON h.id = o.hub_id"
              + " WHERE o.order_id IN (:orderIds)",
      nativeQuery = true)
  List<DistributionCandidateProjection> findCandidates(
      @Param("orderIds") Collection<Long> orderIds);

  /**
   * Reassign count per order, from the three assign_type markers that represent a genuine reassign.
   *
   * <p>Returns {@code [orderId, count]} pairs; orders with no history row are simply absent, and
   * the caller defaults them to 0. There is no assign_type for the original assignment, so counting
   * these three markers is the only available signal — same convention as the original.
   */
  @Query(
      value =
          "SELECT oh.order_id AS orderId,"
              + "       SUM(CASE WHEN oh.assign_type IN"
              + "            ('Cron Reassign','Normal Reassign','Forcefully Reassign')"
              + "            THEN 1 ELSE 0 END) AS cnt"
              + "  FROM "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order_history oh"
              + " WHERE oh.order_id IN (:orderIds)"
              + " GROUP BY oh.order_id",
      nativeQuery = true)
  List<Object[]> findReassignCounts(@Param("orderIds") Collection<Long> orderIds);

  /**
   * Orders whose assigned SP rejected the job after {@code cutoff} — the D-1 bypass signal.
   *
   * <p>Matched on message text because neither the original assignment nor the rejection writes an
   * {@code assign_type}. The single {@code LIKE} covers both live message variants empapi emits
   * ("...due to job canelled by - ... due to job rejection by sp" and "...due to job rejection by
   * sp"), so do not tighten it to an exact match.
   */
  @Query(
      value =
          "SELECT DISTINCT oh.order_id"
              + "  FROM "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order_history oh"
              + " WHERE oh.order_id IN (:orderIds)"
              + "   AND oh.log_at >= :cutoff"
              + "   AND oh.message LIKE '%due to job rejection by sp%'",
      nativeQuery = true)
  List<Long> findSpRejectedAfterCutoff(
      @Param("orderIds") Collection<Long> orderIds, @Param("cutoff") Timestamp cutoff);
}
