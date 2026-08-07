package com.yesmadam.schedulerservice.jobs.sebaltagging.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SpSebalSubCategoryTagLedger;

/**
 * Repository for the append-only audit ledger ({@code tbl_sp_sebal_subcategory_tag_ledgers}) —
 * genuinely append-only: every method here either inserts (via {@code save}/{@code saveAll} on
 * the base {@link JpaRepository}) or reads. Nothing updates an existing row.
 */
@Repository
public interface SpSebalSubCategoryTagLedgerRepository
    extends JpaRepository<SpSebalSubCategoryTagLedger, Long> {

  /**
   * Every sub-category this run's chunks wrote at least one {@code DELTA} row for — the finalize
   * step's re-tiering scope.
   *
   * <p>Currently a <b>full table scan</b>: {@code run_id} is not indexed. Tolerable today (one call
   * per finalize, a few seconds against the 80s finalize as a whole) but the cost grows with the
   * table, and a FULL run adds roughly a million rows to it. Adding
   * {@code index (run_id, row_type)} turns this into the cheap lookup it reads like:
   *
   * <pre>{@code
   * ALTER TABLE ysmdm_admin.tbl_sp_sebal_subcategory_tag_ledgers
   *   ADD INDEX idx_run_id_row_type (run_id, row_type);
   * }</pre>
   */
  @Query(
      value =
          "select distinct sub_category_id from ysmdm_admin.tbl_sp_sebal_subcategory_tag_ledgers"
              + " where run_id = :runId",
      nativeQuery = true)
  List<Integer> findDistinctSubCategoryIdsByRunId(@Param("runId") String runId);
}
