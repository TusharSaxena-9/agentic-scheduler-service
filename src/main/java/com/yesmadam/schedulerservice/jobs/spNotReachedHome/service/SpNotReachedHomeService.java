package com.yesmadam.schedulerservice.jobs.spNotReachedHome.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.spNotReachedHome.model.ReachedHomeUpdate;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.PendingNotReachedHomeProjection;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.SpLatestLocationProjection;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.projections.SpNotReachedHomeCandidateProjection;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.repository.SpNotReachedHomeBatchDao;
import com.yesmadam.schedulerservice.jobs.spNotReachedHome.repository.SpNotReachedHomeRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Business logic for the SP-not-reached-home tracker. Ports cron's {@code
 * SpNotReachedHomeCron.trackSpNotReachedHome} (SAAB-90) and the three DAO operations behind it.
 *
 * <p><b>The reader/writer split is load-bearing, not a style choice.</b> SAAB-90's Phase 3 exists
 * because the original {@code INSERT...SELECT} held InnoDB shared locks over every row it scanned
 * in {@code tbl_sp_location_lasthour} until commit, and empapi's location writer blocked behind
 * them — producing latency spikes in empapi and appserver every five minutes between 20:00 and
 * 23:00. cron solved it with an explicit {@code readerSessionFactory} plus a {@code
 * StatelessSession}; here the equivalent is {@code @Transactional(readOnly = true)}, which {@code
 * ReadWriteRoutingDataSource} routes to the replica. The effect is the same — the master never
 * takes those shared locks — but it means <b>reads and writes cannot share a transaction</b>, so
 * each operation below is deliberately split into a read step and a write step.
 *
 * <p>Two consequences of that split, both intentional:
 *
 * <ul>
 *   <li>Each step is its own transaction, which is exactly SAAB-90's "Fix 4" (method-level rather
 *       than class-level {@code @Transactional}): locks are held for one operation, not all three.
 *   <li>{@link #autoMarkReachedHome()} reads its pending rows from the <b>writer</b>, because they
 *       were written moments earlier in the same cycle and replica lag would make the tracker skip
 *       them. Only the location lookup — written by empapi, not by this job — goes to the replica.
 * </ul>
 *
 * <p>Preserved from the original rather than "improved": the eligibility query and the delete both
 * take their day boundary from MySQL's {@code CURDATE()}, and the location window from {@code NOW()
 * - INTERVAL 10 MINUTE}. Because the server evaluates them, this job has no dependency on the JVM's
 * default timezone.
 */
@Slf4j
@Service
public class SpNotReachedHomeService {

  /** Marker written into {@code operated_by} when the tracker, not a human, closed the row. */
  private static final String OPERATED_BY_SYSTEM = "SYSTEM";

  private static final double EARTH_RADIUS_METRES = 6371000;

  @Value("${spnotreachedhome.distance.threshold.metres:300}")
  private int distanceThreshold;

  private final SpNotReachedHomeRepository repository;
  private final SpNotReachedHomeBatchDao batchDao;

  public SpNotReachedHomeService(
      SpNotReachedHomeRepository repository, SpNotReachedHomeBatchDao batchDao) {
    this.repository = repository;
    this.batchDao = batchDao;
  }

  /**
   * Drops today's rows for SPs who have since picked up another job — they are legitimately still
   * out, so tracking them as "not home" would be wrong.
   *
   * <p>Runs first, matching the original's ordering: clearing these before the insert stops a stale
   * row from being refreshed by the UPSERT instead of removed.
   *
   * @return rows deleted
   */
  @Transactional
  public int removeSpWithActiveJob() {
    int deleted = repository.deleteSpWithActiveJob();
    log.info("event=sp_not_reached_home_active_job_rows_removed deleted={}", deleted);
    return deleted;
  }

  /**
   * Finds SPs further than the threshold from home and upserts them into the tracker.
   *
   * <p>Two transactions on purpose — see the class note. The read runs on the replica; only the
   * write touches the master.
   *
   * @return rows upserted
   */
  public int insertSpNotReachedHome() {
    List<SpNotReachedHomeCandidateProjection> candidates = findCandidates();
    if (candidates.isEmpty()) {
      log.info("event=sp_not_reached_home_no_candidates threshold_metres={}", distanceThreshold);
      return 0;
    }
    int upserted = upsertCandidates(candidates);
    log.info(
        "event=sp_not_reached_home_upserted candidates={} upserted={} threshold_metres={}",
        candidates.size(),
        upserted,
        distanceThreshold);
    return upserted;
  }

  /** Replica read — keeps the shared locks off the master. */
  @Transactional(readOnly = true)
  public List<SpNotReachedHomeCandidateProjection> findCandidates() {
    return repository.findCandidatesFurtherThan(distanceThreshold);
  }

  /** Master write. */
  @Transactional
  public int upsertCandidates(List<SpNotReachedHomeCandidateProjection> candidates) {
    return batchDao.upsertCandidates(candidates);
  }

  /**
   * Marks SPs who have since come within the threshold of home.
   *
   * <p>Distance is recomputed in Java here rather than in SQL — the original does the same, because
   * the pending rows and the latest locations come from two different queries against two different
   * connections and cannot be joined server-side.
   *
   * @return rows marked as reached home
   */
  public int autoMarkReachedHome() {
    List<PendingNotReachedHomeProjection> pending = findPending();
    if (pending.isEmpty()) {
      log.info("event=sp_not_reached_home_none_pending");
      return 0;
    }

    List<Integer> spIds =
        pending.stream()
            .map(PendingNotReachedHomeProjection::getSpId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    if (spIds.isEmpty()) {
      return 0;
    }

    Map<Integer, double[]> latestBySpId = latestLocationsBySpId(spIds);
    if (latestBySpId.isEmpty()) {
      log.info("event=sp_not_reached_home_no_recent_locations pending={}", pending.size());
      return 0;
    }

    List<ReachedHomeUpdate> reachedHome = new ArrayList<>();
    for (PendingNotReachedHomeProjection row : pending) {
      double[] current = latestBySpId.get(row.getSpId());
      if (current == null) {
        continue;
      }
      Double homeLat = parseCoordinate(row.getHomeLat());
      Double homeLon = parseCoordinate(row.getHomeLon());
      if (homeLat == null || homeLon == null) {
        continue;
      }
      double distance = haversineMetres(homeLat, homeLon, current[0], current[1]);
      if (distance <= distanceThreshold) {
        reachedHome.add(new ReachedHomeUpdate(row.getId(), current[0], current[1]));
      }
    }

    if (reachedHome.isEmpty()) {
      log.info("event=sp_not_reached_home_none_returned pending={}", pending.size());
      return 0;
    }

    int marked = markReachedHome(reachedHome);
    log.info(
        "event=sp_not_reached_home_marked pending={} marked={} operated_by={}",
        pending.size(),
        marked,
        OPERATED_BY_SYSTEM);
    return marked;
  }

  /**
   * Writer read — these rows were written earlier in this same cycle, so the replica may not have
   * them yet. Deliberately not {@code readOnly}.
   */
  @Transactional
  public List<PendingNotReachedHomeProjection> findPending() {
    return repository.findPendingForToday();
  }

  /** Replica read — {@code tbl_sp_location_lasthour} is written by empapi, not by this job. */
  @Transactional(readOnly = true)
  public Map<Integer, double[]> latestLocationsBySpId(List<Integer> spIds) {
    Map<Integer, double[]> bySpId = new HashMap<>();
    for (SpLatestLocationProjection location : repository.findLatestLocations(spIds)) {
      if (location.getSpId() == null || location.getLat() == null || location.getLon() == null) {
        continue;
      }
      bySpId.put(location.getSpId(), new double[] {location.getLat(), location.getLon()});
    }
    return bySpId;
  }

  /** Master write. */
  @Transactional
  public int markReachedHome(List<ReachedHomeUpdate> updates) {
    return batchDao.markReachedHome(updates);
  }

  /**
   * {@code tbl_sp_address.lat}/{@code lng} are strings, and the query already excludes null and
   * empty. Anything else non-numeric is skipped rather than failing the cycle for every other SP.
   */
  private Double parseCoordinate(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    try {
      return Double.valueOf(raw.trim());
    } catch (NumberFormatException ex) {
      log.warn("event=sp_not_reached_home_bad_coordinate value={}", raw);
      return null;
    }
  }

  /**
   * Great-circle distance in metres. The {@code acos} argument is clamped to [-1, 1] because
   * floating-point error on near-identical points can push it marginally outside the domain and
   * yield {@code NaN}. Same guard as the SQL form.
   */
  private double haversineMetres(
      double homeLat, double homeLon, double currentLat, double currentLon) {
    double cosAngle =
        Math.max(
            -1.0,
            Math.min(
                1.0,
                Math.cos(Math.toRadians(homeLat))
                        * Math.cos(Math.toRadians(currentLat))
                        * Math.cos(Math.toRadians(currentLon) - Math.toRadians(homeLon))
                    + Math.sin(Math.toRadians(homeLat)) * Math.sin(Math.toRadians(currentLat))));
    return EARTH_RADIUS_METRES * Math.acos(cosAngle);
  }
}
