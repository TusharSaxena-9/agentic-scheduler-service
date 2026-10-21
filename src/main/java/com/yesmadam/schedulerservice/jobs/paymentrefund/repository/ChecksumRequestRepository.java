package com.yesmadam.schedulerservice.jobs.paymentrefund.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.paymentrefund.model.ChecksumRequestEntity;

@Repository
public interface ChecksumRequestRepository extends JpaRepository<ChecksumRequestEntity, Long> {

  /**
   * Access path: no index on this table beyond the primary key as of this migration (same as the
   * source query in {@code shortlink}) — a full scan bounded by {@code successAt}. The window is a
   * fixed 5 minutes, so this stays cheap; revisit if the table grows enough for this to matter.
   */
  List<ChecksumRequestEntity> findAllByOrderCreatedAndSuccessAtBetweenAndPayingFor(
      boolean orderCreated, Timestamp from, Timestamp to, String payingFor);
}
