package com.yesmadam.schedulerservice.jobs.cinntraPassbook.model;

import java.util.Map;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Everything one chunk needs to build its SAP requests, bulk-loaded up front in one read-only
 * transaction instead of tracking's per-row {@code findOne} calls. Lives only inside the chunk
 * activity — it holds entities and never crosses the activity boundary (CLAUDE.md rule 9).
 *
 * <p>Tracking's single-row lookups are {@code uniqueResult()}, which throws when two rows match. The
 * series and account-code maps keep that: a key with more than one row is recorded as ambiguous and
 * {@link #series}/{@link #accountCode} throw for it, failing that entry exactly as tracking would.
 */
@Getter
@AllArgsConstructor
public class CinntraPassbookLookups {

  private final Map<Integer, CinntraPassbookSp> spById;

  private final Map<Integer, CinntraPassbookHub> hubById;

  /** {@code spId -> typeCode -> mapping}, lowest id first wins, matching tracking's {@code LIMIT 1}. */
  private final Map<Integer, Map<Integer, CinntraPassbookSapMapping>> sapMappingBySpAndType;

  /** SPs working in any excluded category (84, 87, 83, 88, 89). */
  private final Set<Integer> excludedCategorySpIds;

  private final Map<String, CinntraPassbookSapSeries> seriesByWhsCode;

  private final Set<String> ambiguousWhsCodes;

  private final Map<Integer, String> accountCodeByReasonCode;

  private final Set<Integer> ambiguousReasonCodes;

  /** Orders that exist, mapped to their category's costing code (which may itself be {@code null}). */
  private final Map<Long, String> costingCodeByOrderId;

  public CinntraPassbookSp sp(Integer spId) {
    return spId == null ? null : spById.get(spId);
  }

  public CinntraPassbookHub hub(Integer hubId) {
    return hubId == null ? null : hubById.get(hubId);
  }

  public CinntraPassbookSapMapping sapMapping(Integer spId, int typeCode) {
    Map<Integer, CinntraPassbookSapMapping> byType = spId == null ? null : sapMappingBySpAndType.get(spId);
    return byType == null ? null : byType.get(typeCode);
  }

  public boolean isExcludedCategorySp(Integer spId) {
    return excludedCategorySpIds.contains(spId);
  }

  /** Tracking's {@code getSeriesbyWhsCode}: {@code null} when absent, throws when not unique. */
  public CinntraPassbookSapSeries series(String whsCode) {
    if (whsCode != null && ambiguousWhsCodes.contains(whsCode)) {
      throw new IllegalStateException("More than one tbl_sap_series row for sapWhsCode " + whsCode);
    }
    return whsCode == null ? null : seriesByWhsCode.get(whsCode);
  }

  /** Tracking's {@code getAccountCodeByReasonCode}: {@code null} when absent, throws when not unique. */
  public String accountCode(Integer reasonCode) {
    if (ambiguousReasonCodes.contains(reasonCode)) {
      throw new IllegalStateException("More than one tbl_payment_action_sap row for reason_code " + reasonCode);
    }
    return accountCodeByReasonCode.get(reasonCode);
  }

  public boolean orderExists(Long orderId) {
    return orderId != null && costingCodeByOrderId.containsKey(orderId);
  }

  public String costingCode(Long orderId) {
    return orderId == null ? null : costingCodeByOrderId.get(orderId);
  }
}
