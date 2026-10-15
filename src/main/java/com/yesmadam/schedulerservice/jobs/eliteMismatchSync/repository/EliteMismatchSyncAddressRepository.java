package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncAddress;

/**
 * Mirrors tracking's {@code UtilitiesDao#findByProperties(Address.class, Map.of("user_id", ...,
 * "isDefault", true))} — a Criteria query capped at {@code setMaxResults(1)}, so {@code First}
 * here reproduces the same "never throws on multiple matches" behavior.
 */
@Repository
public interface EliteMismatchSyncAddressRepository extends JpaRepository<EliteMismatchSyncAddress, Long> {

  Optional<EliteMismatchSyncAddress> findFirstByUserIdAndIsDefault(Long userId, Boolean isDefault);
}
