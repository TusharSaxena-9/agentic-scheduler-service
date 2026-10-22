package com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookEntry;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookAccountCodeRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookHubRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookOrderCostingProjection;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapMappingRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSapSeriesRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSpAddressRow;
import com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.CinntraPassbookSpRow;

/**
 * Every read the passbook-to-SAP sync makes that can tolerate replica lag — so every method is
 * read-only and goes to the reader (CLAUDE.md rule 13). The reads that must see this job's own
 * writes are on {@link CinntraPassbookWriteRepository} instead.
 *
 * <p>The selection queries return ids only (rule 9). The lookups are keyed on one chunk's ids, so
 * every {@code IN} list is at most one chunk long — far below the size where rule 15's measurement
 * pushed the optimizer off an index.
 */
@Transactional(readOnly = true)
public interface CinntraPassbookReadRepository extends Repository<CinntraPassbookEntry, Long> {

  /**
   * Package of the row DTOs the lookups construct; HQL constructor expressions need it spelled out.
   */
  String ROWS = "com.yesmadam.schedulerservice.jobs.cinntrapassbook.projections.";

  /**
   * tracking's {@code getspPassbookforHalfanHour}: every entry in the window, newest first.
   *
   * <p>Access path: range scan on {@code tbl_sp_passbook.date}. That index is not visible from this
   * repo — tracking runs the same predicate every 30 minutes — so verify it before enabling;
   * without it this is a full scan of the passbook: {@code ALTER TABLE ysmdm_admin.tbl_sp_passbook
   * ADD INDEX idx_sp_passbook_date (date)}.
   */
  @Query(
      "SELECT p.id FROM CinntraPassbookEntry p WHERE p.date >= :start AND p.date < :end"
          + " ORDER BY p.date DESC, p.id DESC")
  List<Long> findIdsInWindow(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

  /**
   * tracking's {@code getspPassbookPendingRetryInLastTwoHours}: entries not yet marked processed,
   * newest first. Same {@code date} range scan as {@link #findIdsInWindow}.
   */
  @Query(
      "SELECT p.id FROM CinntraPassbookEntry p WHERE p.date >= :start AND p.date < :end"
          + " AND (p.isSapProcessed IS NULL OR p.isSapProcessed = 0)"
          + " ORDER BY p.date DESC, p.id DESC")
  List<Long> findUnprocessedIdsInWindow(
      @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

  /**
   * tracking's {@code getspPassbookYesterdayByReasonCode}: one reason code over the window,
   * skipping rows already posted. Ordered by id so a chunked run walks it deterministically
   * (tracking's Criteria query had no order). Same {@code date} range scan, filtered on {@code
   * reasonCode}.
   */
  @Query(
      "SELECT p.id FROM CinntraPassbookEntry p WHERE p.date >= :start AND p.date < :end"
          + " AND p.reasonCode = :reasonCode"
          + " AND (p.isSapProcessed IS NULL OR p.isSapProcessed = 0)"
          + " ORDER BY p.id")
  List<Long> findUnprocessedIdsByReasonCode(
      @Param("reasonCode") Integer reasonCode,
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end);

  /**
   * tracking's {@code getspPassbookPendingConvenience}: reason 102, unprocessed, non-franchise,
   * non-laser SP, and no convenience document (requestType 102) recorded for the order yet.
   *
   * <p>Access path: {@code date} range scan on the passbook, primary-key joins to {@code tbl_sp}
   * and {@code tbl_hub}, and a per-row probe of {@code tbl_sap_doc_entries.orderId} for the {@code
   * NOT EXISTS}.
   */
  @Query(
      "SELECT p.id FROM CinntraPassbookEntry p, CinntraPassbookSp s, CinntraPassbookHub h"
          + " WHERE s.id = p.spid AND h.id = s.hubId"
          + " AND p.reasonCode = 102"
          + " AND (p.isSapProcessed IS NULL OR p.isSapProcessed = 0)"
          + " AND p.date >= :start AND p.date < :end"
          + " AND (h.isFranchise = false OR h.isFranchise IS NULL)"
          + " AND LOWER(s.name) NOT LIKE '%laser%'"
          + " AND NOT EXISTS (SELECT d.id FROM CinntraPassbookSapDocEntry d"
          + "   WHERE d.orderId = p.orderId AND d.requestType = 102)"
          + " ORDER BY p.date DESC, p.id DESC")
  List<Long> findPendingConvenienceIds(
      @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

  /** Primary-key lookup on {@code tbl_sp}. */
  @Query(
      "SELECT new "
          + ROWS
          + "CinntraPassbookSpRow(s.id, s.name, s.hubId)"
          + " FROM CinntraPassbookSp s WHERE s.id IN :ids")
  List<CinntraPassbookSpRow> findSps(@Param("ids") Collection<Integer> ids);

  /** Primary-key lookup on {@code tbl_hub}. */
  @Query(
      "SELECT new "
          + ROWS
          + "CinntraPassbookHubRow(h.id, h.hub, h.state, h.isFranchise, h.sapWhsCode,"
          + " h.sapBplId, h.cgst, h.igst, h.sgst)"
          + " FROM CinntraPassbookHub h WHERE h.id IN :ids")
  List<CinntraPassbookHubRow> findHubs(@Param("ids") Collection<Integer> ids);

  /**
   * tracking's {@code getSapIdbySpIdandType}, for a chunk's SPs and every type code the job reads
   * (104, 105, 106) in one query. Lowest id first so "first row wins" is deterministic where
   * tracking's {@code LIMIT 1} had no {@code ORDER BY}.
   *
   * <p>Access path: lookup on {@code tbl_sap_mapping.spId}; index not verifiable from this repo.
   */
  @Query(
      "SELECT new "
          + ROWS
          + "CinntraPassbookSapMappingRow(m.id, m.spId, m.sapId, m.typeCode)"
          + " FROM CinntraPassbookSapMapping m WHERE m.spId IN :spIds AND m.typeCode IN :typeCodes"
          + " ORDER BY m.id")
  List<CinntraPassbookSapMappingRow> findSapMappings(
      @Param("spIds") Collection<Integer> spIds, @Param("typeCodes") Collection<Integer> typeCodes);

  /**
   * Which of these SPs work in any of the excluded categories — tracking loads every working
   * category per SP and filters in Java; this asks only the question it answers.
   *
   * <p>Access path: lookup on {@code tbl_sp_working_categories.sp_id}.
   */
  @Query(
      "SELECT DISTINCT w.spId FROM CinntraPassbookWorkingCategory w"
          + " WHERE w.spId IN :spIds AND w.catId IN :catIds")
  List<Integer> findSpIdsWorkingInCategories(
      @Param("spIds") Collection<Integer> spIds, @Param("catIds") Collection<Integer> catIds);

  /**
   * tracking's {@code getSeriesbyWhsCode}, for a chunk's warehouses. {@code tbl_sap_series} is one
   * row per warehouse.
   */
  @Query(
      "SELECT new "
          + ROWS
          + "CinntraPassbookSapSeriesRow(s.id, s.sapWhsCode, s.salesOrderSeries,"
          + " s.arInvoiceSeries, s.incomingPaymentSeries, s.outgoingPaymentSeries)"
          + " FROM CinntraPassbookSapSeries s WHERE s.sapWhsCode IN :whsCodes")
  List<CinntraPassbookSapSeriesRow> findSeries(@Param("whsCodes") Collection<String> whsCodes);

  /**
   * tracking's {@code getAccountCodeByReasonCode}, for a chunk's reason codes. A tiny reference
   * table.
   */
  @Query(
      "SELECT new "
          + ROWS
          + "CinntraPassbookAccountCodeRow(a.reasonCode, a.accountCode)"
          + " FROM CinntraPassbookAccountCode a WHERE a.reasonCode IN :reasonCodes")
  List<CinntraPassbookAccountCodeRow> findAccountCodes(
      @Param("reasonCodes") Collection<Integer> reasonCodes);

  /**
   * tracking's per-entry {@code findOne(Order)} plus {@code getCostingCodebyCategory}, folded into
   * one query (rule 15). An order missing from the result does not exist.
   *
   * <p>Access path: primary key on {@code ysmdm_users.tbl_order}, primary key on {@code
   * tbl_category}.
   */
  @Query(
      "SELECT o.orderId AS orderId, c.sapOcrdCode AS costingCode FROM CinntraPassbookOrder o"
          + " LEFT JOIN CinntraPassbookCategory c ON c.id = o.categoryId"
          + " WHERE o.orderId IN :orderIds")
  List<CinntraPassbookOrderCostingProjection> findOrderCostingCodes(
      @Param("orderIds") Collection<Long> orderIds);

  /**
   * tracking's {@code getAddressBySpId} — only called when SAP rejects a post with "no matching
   * record", which is rare. Lowest id first where tracking's {@code setMaxResults(1)} had no order.
   *
   * <p>Access path: lookup on {@code tbl_sp_address.sp_id}.
   */
  @Query(
      "SELECT new "
          + ROWS
          + "CinntraPassbookSpAddressRow(a.id, a.spId, a.address1, a.city, a.pincode)"
          + " FROM CinntraPassbookSpAddress a WHERE a.spId = :spId ORDER BY a.id")
  List<CinntraPassbookSpAddressRow> findSpAddresses(@Param("spId") Integer spId);
}
