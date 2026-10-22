package com.yesmadam.schedulerservice.jobs.cinntraPassbook.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookAccountCode;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookEntry;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookHub;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookLookups;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSapMapping;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSapSeries;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSp;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSyncKind;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.projections.CinntraPassbookOrderCostingProjection;
import com.yesmadam.schedulerservice.jobs.cinntraPassbook.repository.CinntraPassbookReadRepository;
import com.yesmadam.schedulerservice.utils.QueryTimer;

/**
 * Loads, for one chunk, every lookup tracking's per-entry code makes one row at a time — the SP,
 * its hub, its SAP mappings, its working categories, the warehouse series, the account code, the
 * order's costing code — as one query per table.
 *
 * <p>A separate bean from the service so the {@code readOnly} transaction is applied by the Spring
 * proxy (CLAUDE.md rule 13): the whole load runs on one replica connection, released before the
 * first SAP call.
 */
@Component
public class CinntraPassbookReader {

  /** Categories whose SPs are never posted to SAP — tracking's {@code excludeCatIds}. */
  static final List<Integer> EXCLUDED_CATEGORY_IDS = List.of(84, 87, 83, 88, 89);

  static final int SAP_MAPPING_TYPE_SP = 106;
  static final int SAP_MAPPING_TYPE_SAVING = 105;
  static final int SAP_MAPPING_TYPE_MATERIAL = 104;

  private final CinntraPassbookReadRepository readRepository;

  public CinntraPassbookReader(CinntraPassbookReadRepository readRepository) {
    this.readRepository = readRepository;
  }

  /** Bulk lookups for {@code entries}. Only the journal sync needs account codes and the 104/105 mappings, and only the invoice kinds need costing codes. */
  @Transactional(readOnly = true)
  public CinntraPassbookLookups loadLookups(
      CinntraPassbookSyncKind kind, List<CinntraPassbookEntry> entries, QueryTimer timer) {
    Set<Integer> spIds = distinct(entries.stream().map(CinntraPassbookEntry::getSpid));

    Map<Integer, CinntraPassbookSp> spById = new HashMap<>();
    if (!spIds.isEmpty()) {
      for (CinntraPassbookSp sp : timer.time("findSps", () -> readRepository.findSps(spIds))) {
        spById.put(sp.getId(), sp);
      }
    }

    Set<Integer> hubIds = distinct(spById.values().stream().map(CinntraPassbookSp::getHubId));
    Map<Integer, CinntraPassbookHub> hubById = new HashMap<>();
    if (!hubIds.isEmpty()) {
      for (CinntraPassbookHub hub : timer.time("findHubs", () -> readRepository.findHubs(hubIds))) {
        hubById.put(hub.getId(), hub);
      }
    }

    List<Integer> typeCodes =
        kind == CinntraPassbookSyncKind.JOURNAL
            ? List.of(SAP_MAPPING_TYPE_SP, SAP_MAPPING_TYPE_SAVING, SAP_MAPPING_TYPE_MATERIAL)
            : List.of(SAP_MAPPING_TYPE_SP);
    Map<Integer, Map<Integer, CinntraPassbookSapMapping>> mappings = new HashMap<>();
    Set<Integer> excludedCategorySpIds = new HashSet<>();
    if (!spIds.isEmpty()) {
      for (CinntraPassbookSapMapping m :
          timer.time("findSapMappings", () -> readRepository.findSapMappings(spIds, typeCodes))) {
        // Ordered by id, so putIfAbsent keeps the lowest — tracking's LIMIT 1.
        mappings.computeIfAbsent(m.getSpId(), k -> new HashMap<>()).putIfAbsent(m.getTypeCode(), m);
      }
      excludedCategorySpIds.addAll(
          timer.time(
              "findExcludedCategorySps",
              () -> readRepository.findSpIdsWorkingInCategories(spIds, EXCLUDED_CATEGORY_IDS)));
    }

    Set<String> whsCodes = distinct(hubById.values().stream().map(CinntraPassbookHub::getSapWhsCode));
    Map<String, CinntraPassbookSapSeries> seriesByWhsCode = new HashMap<>();
    Set<String> ambiguousWhsCodes = new HashSet<>();
    if (!whsCodes.isEmpty()) {
      for (CinntraPassbookSapSeries s :
          timer.time("findSeries", () -> readRepository.findSeries(whsCodes))) {
        if (seriesByWhsCode.putIfAbsent(s.getSapWhsCode(), s) != null) {
          ambiguousWhsCodes.add(s.getSapWhsCode());
        }
      }
    }

    Map<Integer, String> accountCodeByReasonCode = new HashMap<>();
    Set<Integer> ambiguousReasonCodes = new HashSet<>();
    Set<Integer> reasonCodes = distinct(entries.stream().map(CinntraPassbookEntry::getReasonCode));
    if (kind == CinntraPassbookSyncKind.JOURNAL && !reasonCodes.isEmpty()) {
      for (CinntraPassbookAccountCode a :
          timer.time("findAccountCodes", () -> readRepository.findAccountCodes(reasonCodes))) {
        if (accountCodeByReasonCode.containsKey(a.getReasonCode())) {
          ambiguousReasonCodes.add(a.getReasonCode());
        }
        accountCodeByReasonCode.put(a.getReasonCode(), a.getAccountCode());
      }
    }

    Map<Long, String> costingCodeByOrderId = new HashMap<>();
    Set<Long> orderIds = distinct(entries.stream().map(CinntraPassbookEntry::getOrderId));
    boolean invoiceKind =
        kind == CinntraPassbookSyncKind.COMMISSION || kind == CinntraPassbookSyncKind.CONVENIENCE;
    if (invoiceKind && !orderIds.isEmpty()) {
      for (CinntraPassbookOrderCostingProjection o :
          timer.time("findOrderCostingCodes", () -> readRepository.findOrderCostingCodes(orderIds))) {
        costingCodeByOrderId.put(o.getOrderId(), o.getCostingCode());
      }
    }

    return new CinntraPassbookLookups(
        spById,
        hubById,
        mappings,
        excludedCategorySpIds,
        seriesByWhsCode,
        ambiguousWhsCodes,
        accountCodeByReasonCode,
        ambiguousReasonCodes,
        costingCodeByOrderId);
  }

  private static <T> Set<T> distinct(Stream<T> values) {
    return values.filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
