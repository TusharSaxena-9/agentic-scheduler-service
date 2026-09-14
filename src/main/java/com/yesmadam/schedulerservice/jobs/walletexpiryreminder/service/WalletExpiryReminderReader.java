package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.service;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.projections.WalletExpiryCandidateProjection;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.repository.WalletExpiryCandidateRepository;

/**
 * Read-only side of the wallet-expiry reminder, kept as its own bean so the {@code readOnly}
 * transaction actually reaches the reader replica — an in-bean method call would bypass the
 * Spring proxy and silently run on the writer.
 */
@Service
public class WalletExpiryReminderReader {

  private final WalletExpiryCandidateRepository candidateRepository;

  public WalletExpiryReminderReader(WalletExpiryCandidateRepository candidateRepository) {
    this.candidateRepository = candidateRepository;
  }

  @Transactional(readOnly = true)
  public List<WalletExpiryCandidateProjection> fetchCandidates(Timestamp dayStart, Timestamp dayEnd) {
    return candidateRepository.findCandidates(dayStart, dayEnd);
  }
}
