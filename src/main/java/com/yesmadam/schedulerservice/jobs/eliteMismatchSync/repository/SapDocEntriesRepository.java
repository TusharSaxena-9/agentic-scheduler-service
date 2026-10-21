package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.SapDocEntries;

@Repository
public interface SapDocEntriesRepository extends JpaRepository<SapDocEntries, Integer> {

  /**
   * Retry guard for {@code syncChunk}: has any SAP document already been recorded for this
   * subscription id? Same test as the {@code sd.orderId IS NULL} in the selection query.
   *
   * <p>Deliberately <b>not</b> read-only, so it runs on the writer: it must see the row an earlier
   * attempt of the same chunk wrote seconds ago, and the replica may not have it yet (CLAUDE.md rule
   * 13, "Replica lag is real"). Do not "fix" this to {@code readOnly = true}.
   *
   * <p>Access path: lookup on {@code tbl_sap_doc_entries.orderId}, one call per subscription.
   */
  @Transactional
  boolean existsByOrderId(Integer orderId);
}
