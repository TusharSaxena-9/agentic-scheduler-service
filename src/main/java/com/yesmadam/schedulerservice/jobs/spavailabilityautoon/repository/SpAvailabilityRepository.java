package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SPRegistration;

/**
 * The write half of the auto-availability-on job, plus the one read that has to happen on the
 * writer rather than the replica.
 *
 * <p>Job-local rather than added to {@code repository.interfaces.SpRepository}: both statements are
 * specific to this job's semantics (the {@code availability is null or false} guard in particular),
 * and keeping them here means the job's read/write shape is visible in its own package listing.
 *
 * <p><b>Access path (rule 15):</b> both statements are primary-key lookups — {@code tbl_sp.id IN
 * (...)} over a batch bounded by the reader's page size. No scan, no index to add.
 */
@Repository
public interface SpAvailabilityRepository extends JpaRepository<SPRegistration, Integer> {

  /**
   * Of {@code spIds}, those that are still active with availability off.
   *
   * <p>Called from inside the writer's transaction, so it runs on the <b>writer</b>, deliberately
   * not under {@code @Transactional(readOnly = true)} (CLAUDE.md rule 13 — "anything that writes
   * and then verifies its own write must do both on the writer"). The candidate ids arrive from the
   * read replica, which can lag behind a flip this same run has already committed; re-checking here
   * is what keeps the audit rows in step with the rows actually changed. Do not "fix" this by
   * annotating it read-only.
   *
   * @param spIds the candidate batch
   * @return the subset that will actually change
   */
  @Query(
      "select s.id from SPRegistration s "
          + "where s.id in :spIds "
          + "and s.status = true "
          + "and (s.availability is null or s.availability = false)")
  List<Integer> findStillAvailabilityOff(@Param("spIds") List<Integer> spIds);

  /**
   * Flips availability on for the given SPs in one statement.
   *
   * <p>The {@code availability is null or false} guard is repeated here rather than trusting the
   * caller's list: it makes the statement converge rather than accumulate (rule 14), so a retry
   * that re-runs this batch after it already committed matches nothing and updates nothing.
   *
   * @param spIds SPs to turn on
   * @param updatedAt value written to {@code updated_at}
   * @return number of rows actually updated
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "update SPRegistration s set s.availability = true, s.updated_at = :updatedAt "
          + "where s.id in :spIds "
          + "and s.status = true "
          + "and (s.availability is null or s.availability = false)")
  int turnAvailabilityOn(
      @Param("spIds") List<Integer> spIds, @Param("updatedAt") Timestamp updatedAt);
}
