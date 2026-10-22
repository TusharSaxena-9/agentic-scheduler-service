package com.yesmadam.schedulerservice.jobs.cinntraPassbook.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSapSession;

/**
 * Reads the shared SAP session row. {@code findById} is read-only, so it goes to the replica: the
 * session is refreshed every 30 minutes and the previous one stays valid well past a few seconds of lag.
 */
@Repository
public interface CinntraPassbookSapSessionRepository extends JpaRepository<CinntraPassbookSapSession, Integer> {}
