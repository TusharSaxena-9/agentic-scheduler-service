package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncOrder;

/** Mirrors tracking's {@code UtilitiesDao#getOrdersWithEliteFees}. */
@Repository
public interface EliteMismatchSyncOrderRepository extends JpaRepository<EliteMismatchSyncOrder, Long> {

  /**
   * Read-only, so it runs on the reader. A derived method declared here does not inherit Spring
   * Data's read-only default (only the inherited {@code CrudRepository} methods do), and with no
   * transaction at all it would route to the writer (CLAUDE.md rule 13).
   */
  @Transactional(readOnly = true)
  List<EliteMismatchSyncOrder> findByUseridAndEliteFeesIsNotNull(Integer userid);
}
