package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncServiceHub;

/**
 * {@code findById} from {@link JpaRepository} is all this job needs. Tracking's own {@code
 * sendSapRequest} does {@code Optional.ofNullable(findOne(ServiceHub.class, hubId)).orElseGet(()
 * -> findByProperties(ServiceHub.class, Map.of("id", hubId)))} — a redundant two-step lookup,
 * since both branches query the exact same id and can never disagree. This port simplifies to a
 * single equivalent {@code findById} call; the outcome is identical either way.
 */
@Repository
public interface EliteMismatchSyncServiceHubRepository extends JpaRepository<EliteMismatchSyncServiceHub, Integer> {}
