package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapMapping;

/**
 * Mirrors tracking's {@code UtilitiesDao#getSapIdbySpIdandType} — a native {@code ... limit 1}
 * query, so {@code First} here reproduces the same "never throws on multiple matches" behavior.
 * Used for the customer's card-code mapping (type code 110).
 */
@Repository
public interface EliteMismatchSyncSapMappingRepository extends JpaRepository<EliteMismatchSyncSapMapping, Integer> {

  /**
   * Deliberately <b>not</b> read-only, so it runs on the writer: a miss here creates a new
   * Business Partner in SAP, and {@code createSapUser} writes this mapping seconds before the next
   * subscription of the same customer — or a retried attempt — reads it back. The replica may not
   * have it yet, and a stale miss creates a duplicate customer in SAP (CLAUDE.md rule 13, "Replica
   * lag is real"). Do not "fix" this to {@code readOnly = true}.
   */
  @Transactional
  Optional<EliteMismatchSyncSapMapping> findFirstBySpIdAndTypeCode(Integer spId, Integer typeCode);
}
