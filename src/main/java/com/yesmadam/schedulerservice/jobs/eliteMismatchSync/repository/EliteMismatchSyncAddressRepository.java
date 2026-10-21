package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncAddress;

/**
 * Mirrors tracking's {@code UtilitiesDao#findByProperties(Address.class, Map.of("user_id", ...,
 * "isDefault", true))} — a Criteria query capped at {@code setMaxResults(1)}, so {@code First}
 * here reproduces the same "never throws on multiple matches" behavior.
 */
@Repository
public interface EliteMismatchSyncAddressRepository extends JpaRepository<EliteMismatchSyncAddress, Long> {

  /**
   * Read-only, so it runs on the reader. A derived method declared here does not inherit Spring
   * Data's read-only default (only the inherited {@code CrudRepository} methods do), and with no
   * transaction at all it would route to the writer (CLAUDE.md rule 13).
   */
  @Transactional(readOnly = true)
  Optional<EliteMismatchSyncAddress> findFirstByUserIdAndIsDefault(Long userId, Boolean isDefault);
}
