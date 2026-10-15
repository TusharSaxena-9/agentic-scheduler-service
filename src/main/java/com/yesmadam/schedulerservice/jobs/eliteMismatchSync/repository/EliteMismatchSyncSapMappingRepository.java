package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapMapping;

/**
 * Mirrors tracking's {@code UtilitiesDao#getSapIdbySpIdandType} — a native {@code ... limit 1}
 * query, so {@code First} here reproduces the same "never throws on multiple matches" behavior.
 * Used for the customer's card-code mapping (type code 110).
 */
@Repository
public interface EliteMismatchSyncSapMappingRepository extends JpaRepository<EliteMismatchSyncSapMapping, Integer> {

  Optional<EliteMismatchSyncSapMapping> findFirstBySpIdAndTypeCode(Integer spId, Integer typeCode);
}
