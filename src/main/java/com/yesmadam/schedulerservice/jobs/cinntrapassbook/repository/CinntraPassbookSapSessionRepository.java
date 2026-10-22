package com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapSession;

/**
 * Reads the shared SAP session row. {@code findById} is read-only, so it goes to the replica: the
 * session is refreshed every 30 minutes and the previous one stays valid well past a few seconds of
 * lag.
 */
@Repository
public interface CinntraPassbookSapSessionRepository
    extends JpaRepository<CinntraPassbookSapSession, Integer> {

  /**
   * The session id, read on the <b>writer</b>. Used only straight after an in-place re-login, which
   * has just written the row: the replica may still hold the session SAP rejected. Deliberately not
   * read-only (CLAUDE.md rule 13, "Replica lag is real").
   */
  @Transactional
  @Query("SELECT s.sessionId FROM CinntraPassbookSapSession s WHERE s.id = :id")
  Optional<String> findSessionIdOnWriter(@Param("id") Integer id);
}
