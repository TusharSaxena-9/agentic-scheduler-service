package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncYmElite;

/** {@code findById} from {@link JpaRepository} is all this job needs. */
@Repository
public interface EliteMismatchSyncYmEliteRepository extends JpaRepository<EliteMismatchSyncYmElite, Integer> {}
