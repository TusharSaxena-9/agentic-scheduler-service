package com.yesmadam.schedulerservice.jobs.roseorder.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.SpRoseOrderEligibility;
import com.yesmadam.persistence.SpRoseOrderEligibilityLedger;
import com.yesmadam.schedulerservice.jobs.roseorder.repository.SpRoseOrderEligibilityLedgerRepository;
import com.yesmadam.schedulerservice.jobs.roseorder.repository.SpRoseOrderEligibilityRepository;

/**
 * Persists one chunk of Rose Order eligibility results atomically.
 *
 * <p>Kept as a separate bean so the {@code @Transactional} boundary goes through the Spring proxy —
 * as a method on {@link RoseOrderServiceImpl} the self-invocation would silently bypass transaction
 * management.
 *
 * <p>One read-write transaction per chunk means: the current-state lookup for the upsert reads the
 * writer (never a lagging replica), and the current-state and ledger writes commit or roll back
 * together, so the two tables cannot diverge mid-chunk.
 */
@Service
public class RoseOrderEligibilityWriter {

  private final SpRoseOrderEligibilityRepository eligibilityRepository;
  private final SpRoseOrderEligibilityLedgerRepository ledgerRepository;

  public RoseOrderEligibilityWriter(
      SpRoseOrderEligibilityRepository eligibilityRepository,
      SpRoseOrderEligibilityLedgerRepository ledgerRepository) {
    this.eligibilityRepository = eligibilityRepository;
    this.ledgerRepository = ledgerRepository;
  }

  /**
   * Upserts the current-state eligibility rows and appends the audit ledger rows for one chunk in a
   * single transaction. The eligibility flags are taken from the already-built ledger rows.
   *
   * @param ledgersBySpId fully-built ledger rows keyed by SP id
   */
  @Transactional
  public void persistChunk(Map<Integer, SpRoseOrderEligibilityLedger> ledgersBySpId) {
    if (ledgersBySpId.isEmpty()) {
      return;
    }

    Map<Integer, SpRoseOrderEligibility> existingBySpId =
        eligibilityRepository.findBySp_IdIn(List.copyOf(ledgersBySpId.keySet())).stream()
            .collect(Collectors.toMap(e -> e.getSp().getId(), Function.identity()));

    List<SpRoseOrderEligibility> eligibilityToSave = new ArrayList<>(ledgersBySpId.size());
    ledgersBySpId.forEach(
        (spId, ledger) -> {
            // this is done you for requirement that the sp once qualified should not be removed from pool, so for now we will once insert wont disable also only eligible will be inserted
            if (!ledger.getEligible()) return;
          SpRoseOrderEligibility eligibility = existingBySpId.get(spId);
          if (eligibility == null) {
            eligibility = new SpRoseOrderEligibility();
            eligibility.setSp(ledger.getSp());
          }
          eligibility.setAssignedToCompleteThresholdPassed(ledger.getAssignedToCompleteThresholdPassed());
          eligibility.setAverageRatingThresholdPassed(ledger.getAverageRatingThresholdPassed());
          eligibility.setCompletedOrdersCountPassed(ledger.getCompletedOrdersCountPassed());
          eligibility.setEligible(ledger.getEligible());
          eligibilityToSave.add(eligibility);
        });

    eligibilityRepository.saveAll(eligibilityToSave);
    ledgerRepository.saveAll(new ArrayList<>(ledgersBySpId.values()));
  }
}
