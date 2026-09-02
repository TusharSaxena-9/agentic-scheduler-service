package com.yesmadam.schedulerservice.jobs.spNotReachedHome.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.model.SpNotReachedHome;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.PendingNotReachedHomeProjection;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.SpLatestLocationProjection;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.SpNotReachedHomeCandidateProjection;

/**
 * Reads and the one delete for the SP-not-reached-home tracker, ported from cron's {@code
 * SpNotReachedHomeDaoImpl} (SAAB-90).
 *
 * <p>Every date and time boundary stays inside MySQL ({@code CURDATE()}, {@code NOW() - INTERVAL
 * ...}) exactly as the original wrote it. That is deliberate rather than incidental: the server
 * evaluates them, so none of these queries depend on the JVM's default timezone — unlike the SAP
 * jobs, which bind {@code Timestamp} parameters computed in Java and therefore had to be pinned to
 * Asia/Kolkata explicitly.
 *
 * <p>The 10-minute location window is SAAB-90's "Fix 1": it replaced {@code >= CONCAT(CURDATE(), '
 * 20:00:00')}, which grew its lock scope linearly through the evening and caused the pre-prod
 * latency spikes in empapi. Do not widen it.
 */
@Repository
public interface SpNotReachedHomeRepository extends JpaRepository<SpNotReachedHome, Long> {

  /**
   * SPs whose latest booking today is complete, ended at or after 20:00, is already past its end
   * time, and who are currently more than {@code distanceThreshold} metres from home.
   *
   * <p>Read-only by design — the caller runs it in a {@code readOnly = true} transaction so it is
   * served by the replica. That is the core of SAAB-90's Phase 3 fix: an {@code INSERT...SELECT} on
   * the master took shared locks over every row it scanned in {@code tbl_sp_location_lasthour},
   * blocking empapi's location writes. Splitting the read from the write keeps those locks off the
   * master entirely.
   */
  @Query(
      value =
          "WITH last_completed_sp AS ("
              + "  SELECT o.s_p_assign_by_user AS sp_id, o.order_id AS order_id,"
              + "         o.order_end_time_with_date, o.order_status_code,"
              + "         ROW_NUMBER() OVER (PARTITION BY o.s_p_assign_by_user"
              + "                            ORDER BY o.order_end_time_with_date DESC) AS rn"
              + "  FROM "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order o"
              + "  WHERE o.booking_date >= CURDATE() AND o.booking_date < CURDATE() + INTERVAL 1 DAY"
              + "    AND o.s_p_assign_by_user IS NOT NULL"
              + "), "
              + "latest_completed AS ("
              + "  SELECT * FROM last_completed_sp WHERE rn = 1"
              + "    AND order_status_code IN (5, 6)"
              + "    AND TIME(order_end_time_with_date) >= '20:00:00'"
              + "), "
              + "latest_location AS ("
              + "  SELECT l.spid AS sp_id, l.lat AS current_lat, l.lon AS current_lon"
              + "  FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_location_lasthour l"
              + "  INNER JOIN ("
              + "    SELECT spid, MAX(location_time) AS max_time"
              + "    FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_location_lasthour"
              + "    WHERE location_time >= NOW() - INTERVAL 10 MINUTE"
              + "    GROUP BY spid"
              + "  ) lh ON l.spid = lh.spid AND l.location_time = lh.max_time"
              + "), "
              + "sp_home AS ("
              + "  SELECT sa.sp_id, sa.lat AS home_lat, sa.lng AS home_lon"
              + "  FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_address sa"
              + "  WHERE sa.lat IS NOT NULL AND sa.lng IS NOT NULL"
              + "    AND sa.lat != '' AND sa.lng != ''"
              + "), "
              + "computed AS ("
              + "  SELECT lc.sp_id                        AS spId,"
              + "         sp.name                         AS spName,"
              + "         lc.order_id                     AS lastOrderId,"
              + "         lc.order_end_time_with_date     AS lastOrderEndTime,"
              + "         ll.current_lat                  AS currentLat,"
              + "         ll.current_lon                  AS currentLon,"
              + "         sh.home_lat                     AS homeLat,"
              + "         sh.home_lon                     AS homeLon,"
              // Haversine, with the acos() domain clamped so float error cannot push the argument
              // outside [-1, 1] and yield NULL.
              + "         ROUND(6371000 * ACOS(GREATEST(-1, LEAST(1,"
              + "             COS(RADIANS(sh.home_lat)) * COS(RADIANS(ll.current_lat))"
              + "           * COS(RADIANS(ll.current_lon) - RADIANS(sh.home_lon))"
              + "           + SIN(RADIANS(sh.home_lat)) * SIN(RADIANS(ll.current_lat))"
              + "         ))), 2)                         AS distanceFromHomeMtr"
              + "  FROM latest_completed lc"
              + "  INNER JOIN "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp sp"
              + "          ON sp.id = lc.sp_id AND sp.status = 1"
              + "  INNER JOIN latest_location ll ON ll.sp_id = lc.sp_id"
              + "  INNER JOIN sp_home sh ON sh.sp_id = lc.sp_id"
              + "  WHERE NOW() >= lc.order_end_time_with_date"
              + ") "
              + "SELECT * FROM computed WHERE distanceFromHomeMtr > :distanceThreshold",
      nativeQuery = true)
  List<SpNotReachedHomeCandidateProjection> findCandidatesFurtherThan(
      @Param("distanceThreshold") int distanceThreshold);

  /**
   * Drops today's rows for SPs whose latest booking is no longer complete — i.e. they picked up
   * another job after 20:00, so they are legitimately still out.
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_not_reached_home"
              + " WHERE tracking_date = CURDATE()"
              + "   AND sp_id IN ("
              + "     SELECT sp_id FROM ("
              + "       SELECT o.s_p_assign_by_user AS sp_id,"
              + "              ROW_NUMBER() OVER (PARTITION BY o.s_p_assign_by_user"
              + "                                 ORDER BY o.order_end_time_with_date DESC) AS rn,"
              + "              o.order_status_code"
              + "       FROM "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order o"
              + "       WHERE o.booking_date >= CURDATE()"
              + "         AND o.booking_date < CURDATE() + INTERVAL 1 DAY"
              + "         AND o.s_p_assign_by_user IS NOT NULL"
              + "     ) active_check"
              + "     WHERE rn = 1 AND order_status_code NOT IN (5, 6)"
              + "   )",
      nativeQuery = true)
  int deleteSpWithActiveJob();

  /**
   * Today's still-not-home rows with the SP's home coordinates.
   *
   * <p>Must be served by the <b>writer</b>, not the replica: these rows were written moments
   * earlier in the same cycle, and replica lag would make the tracker skip them. The caller
   * therefore runs this outside a read-only transaction on purpose.
   */
  @Query(
      value =
          "SELECT nrh.id AS id, nrh.sp_id AS spId, hc.lat AS homeLat, hc.lng AS homeLon"
              + " FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_not_reached_home nrh"
              + " INNER JOIN "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_address hc"
              + "         ON hc.sp_id = nrh.sp_id"
              + "        AND hc.lat IS NOT NULL AND hc.lng IS NOT NULL"
              + "        AND hc.lat != '' AND hc.lng != ''"
              + " WHERE nrh.tracking_date = CURDATE() AND nrh.reached_home = 0",
      nativeQuery = true)
  List<PendingNotReachedHomeProjection> findPendingForToday();

  /**
   * Latest ping per SP within the 10-minute window, for the given SPs. Written by empapi, so it is
   * safe on the replica and the caller reads it read-only.
   */
  @Query(
      value =
          "SELECT l.spid AS spId, l.lat AS lat, l.lon AS lon"
              + " FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_location_lasthour l"
              + " INNER JOIN ("
              + "   SELECT spid, MAX(location_time) AS max_time"
              + "   FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_location_lasthour"
              + "   WHERE location_time >= NOW() - INTERVAL 10 MINUTE"
              + "     AND spid IN (:spIds)"
              + "   GROUP BY spid"
              + " ) lh ON l.spid = lh.spid AND l.location_time = lh.max_time",
      nativeQuery = true)
  List<SpLatestLocationProjection> findLatestLocations(@Param("spIds") Collection<Integer> spIds);
}
