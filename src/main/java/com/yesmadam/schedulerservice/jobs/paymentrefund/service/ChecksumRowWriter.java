package com.yesmadam.schedulerservice.jobs.paymentrefund.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.paymentrefund.repository.ChecksumRequestRepository;

/**
 * Isolated into its own bean specifically so {@code @Transactional} takes effect: {@code
 * PaymentRefundOrderCreationService} previously called this find-then-save on itself, and
 * self-invocation never crosses the Spring proxy that applies {@code @Transactional} (CLAUDE.md
 * rule 13), so the annotation was silently dead — the two repository calls ran as two independent
 * transactions instead of one atomic unit. A separate bean makes the call cross a real proxy
 * boundary. Matches the {@code SebalTaggingWriter} / {@code AutoJobDistributionWriter} convention
 * used elsewhere in this service.
 */
@Component
public class ChecksumRowWriter {

  private final ChecksumRequestRepository checksumRequestRepository;

  public ChecksumRowWriter(ChecksumRequestRepository checksumRequestRepository) {
    this.checksumRequestRepository = checksumRequestRepository;
  }

  @Transactional
  public void markHandled(Long checksumId, String createdFrom) {
    checksumRequestRepository
        .findById(checksumId)
        .ifPresent(
            row -> {
              row.setOrderCreated(true);
              row.setCreatedFrom(createdFrom);
              checksumRequestRepository.save(row);
            });
  }
}
