package com.yesmadam.schedulerservice.jobs.cinntraPassbook.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookEntry;

/**
 * Writer-side access for the passbook-to-SAP sync: its one write, and the two reads that must see
 * that write.
 *
 * <p><b>The two reads here are deliberately not read-only</b>, so they run on the writer. They are
 * the retry guard (CLAUDE.md rule 14): a retried chunk has to see the {@code is_sap_processed} flag
 * and the {@code tbl_sap_doc_entries} row an earlier attempt wrote seconds ago, and the replica may
 * not have them yet (rule 13, "Replica lag is real"). Both are keyed on one chunk's ids. Do not
 * "fix" them to {@code readOnly = true}.
 */
public interface CinntraPassbookWriteRepository extends Repository<CinntraPassbookEntry, Long> {

  /** The chunk's passbook rows, fresh. Access path: primary key. */
  @Transactional
  @Query("SELECT p FROM CinntraPassbookEntry p WHERE p.id IN :ids")
  List<CinntraPassbookEntry> findForPosting(@Param("ids") Collection<Long> ids);

  /**
   * Which of these keys already have a SAP document of this request type — the same bulk check
   * tracking's {@code PassbookSapSyncService} makes before posting. Access path: lookup on {@code
   * tbl_sap_doc_entries.orderId}.
   */
  @Transactional
  @Query(
      "SELECT DISTINCT d.orderId FROM CinntraPassbookSapDocEntry d"
          + " WHERE d.orderId IN :keys AND d.requestType = :requestType")
  List<Integer> findPostedKeys(
      @Param("keys") Collection<Integer> keys, @Param("requestType") Integer requestType);

  /**
   * tracking's {@code markSapProcessed}, as a single-column update. Tracking {@code saveOrUpdate}s the
   * whole entity it loaded minutes earlier, which also writes back every other column as it was then;
   * this writes only the flag. Its own transaction, so it commits right after the SAP post it records.
   */
  @Transactional
  @Modifying
  @Query("UPDATE CinntraPassbookEntry p SET p.isSapProcessed = :processed WHERE p.id = :id")
  int updateSapProcessed(@Param("id") Long id, @Param("processed") Integer processed);
}
