package com.yesmadam.schedulerservice.jobs.sebaltagging.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.sebaltagging.model.SebalTaggingTierResult;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.QualifyingFirstOrderProjection;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.RankedTagProjection;
import com.yesmadam.schedulerservice.jobs.sebaltagging.projections.SubsequentOrderProjection;
import com.yesmadam.schedulerservice.jobs.sebaltagging.repository.SebalTaggingCohortRepository;
import com.yesmadam.schedulerservice.jobs.sebaltagging.repository.SpSebalSubCategoryTagLedgerRepository;
import com.yesmadam.schedulerservice.jobs.sebaltagging.repository.SpSebalSubCategoryTagRepository;

/**
 * Read-only SEBAL tagging queries, kept on their own bean — like {@link SebalTaggingWriter} — so
 * each method's {@code @Transactional(readOnly = true)} goes through the Spring proxy instead of
 * being bypassed by self-invocation from {@link SebalTaggingServiceImpl}. The read-only flag is
 * what {@code ReadWriteRoutingDataSource} uses to route to the reader replica; without an active
 * read-only transaction these queries fall through to the writer.
 */
@Service
public class SebalTaggingReader {

  private final SebalTaggingCohortRepository cohortRepository;
  private final SpSebalSubCategoryTagRepository tagRepository;
  private final SpSebalSubCategoryTagLedgerRepository ledgerRepository;

  public SebalTaggingReader(
      SebalTaggingCohortRepository cohortRepository,
      SpSebalSubCategoryTagRepository tagRepository,
      SpSebalSubCategoryTagLedgerRepository ledgerRepository) {
    this.cohortRepository = cohortRepository;
    this.tagRepository = tagRepository;
    this.ledgerRepository = ledgerRepository;
  }

  @Transactional(readOnly = true)
  public List<QualifyingFirstOrderProjection> findQualifyingFirstOrders(
      LocalDate startDate, LocalDate endDate) {
    return cohortRepository.findQualifyingFirstOrders(startDate, endDate);
  }

  @Transactional(readOnly = true)
  public List<SubsequentOrderProjection> findCompletedOrdersForRebookCheck(
      List<Long> userIds, LocalDate lookaheadStart, LocalDate lookaheadEnd) {
    return cohortRepository.findCompletedOrdersForRebookCheck(userIds, lookaheadStart, lookaheadEnd);
  }

  @Transactional(readOnly = true)
  public SebalTaggingTierResult countTierResult() {
    int subCategoriesTiered = (int) tagRepository.countDistinctTieredSubCategories();
    int spsTagged = (int) tagRepository.countByTierIsNotNull();
    return new SebalTaggingTierResult(subCategoriesTiered, spsTagged);
  }

  /** Every sub-category this run's chunks touched — the finalize step's re-tiering scope. */
  @Transactional(readOnly = true)
  public List<Integer> findTouchedSubCategoryIds(String runId) {
    return ledgerRepository.findDistinctSubCategoryIdsByRunId(runId);
  }

  /** Freshly-computed rate/rank/tier for every row in the given sub-categories — a plain read. */
  @Transactional(readOnly = true)
  public List<RankedTagProjection> computeRanks(List<Integer> subCategoryIds) {
    if (subCategoryIds.isEmpty()) {
      return List.of();
    }
    return tagRepository.computeRanksForSubCategories(subCategoryIds);
  }
}
