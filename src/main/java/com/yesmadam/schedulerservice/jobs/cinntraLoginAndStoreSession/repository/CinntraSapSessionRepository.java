package com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.cinntraLoginAndStoreSession.model.CinntraSapSession;

/**
 * {@code findById}/{@code save} from {@link JpaRepository} are all this job needs to reproduce
 * tracking's {@code UtilitiesDao.updateSession} upsert (fetch id {@code 1}, update if present,
 * insert with id {@code 1} otherwise) — see the service layer for that logic.
 */
@Repository
public interface CinntraSapSessionRepository extends JpaRepository<CinntraSapSession, Integer> {}
