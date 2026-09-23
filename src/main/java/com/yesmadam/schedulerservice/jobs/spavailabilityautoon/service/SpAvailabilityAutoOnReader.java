package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.repository.ProductKitApprovalRepository;

/**
 * Every read this job does, on the reader replica.
 *
 * <p>A separate bean from {@link SpAvailabilityAutoOnWriter} on purpose: {@code @Transactional} is
 * applied by a Spring proxy, and a call between two methods on the same bean never crosses it — a
 * {@code readOnly = true} method invoked from its own class would silently join the caller's
 * read-write transaction and run on the primary (CLAUDE.md rule 13). Two beans make the call cross
 * the proxy boundary, and make the job's read/write shape visible in the package listing.
 */
@Service
public class SpAvailabilityAutoOnReader {

  private final ProductKitApprovalRepository approvalRepository;

  public SpAvailabilityAutoOnReader(ProductKitApprovalRepository approvalRepository) {
    this.approvalRepository = approvalRepository;
  }

  /**
   * One page of SPs with a kit approved in the window that are active and still unavailable.
   *
   * @param windowStart inclusive lower bound on {@code approvedat}
   * @param windowEnd exclusive upper bound on {@code approvedat}
   * @param afterSpId exclusive keyset cursor; {@code 0} for the first page
   * @param batchSize maximum ids to return
   * @return up to {@code batchSize} SP ids in ascending order, empty when the window is exhausted
   */
  @Transactional(readOnly = true)
  public List<Integer> findEligibleSpIds(
      Timestamp windowStart, Timestamp windowEnd, Integer afterSpId, int batchSize) {
    return approvalRepository.findEligibleSpIds(
        windowStart, windowEnd, afterSpId, PageRequest.of(0, batchSize));
  }

  /**
   * The same page, minus SPs the notify job has already claimed inside this window.
   *
   * <p>Used by the 20:00 job only. The 23:30 flip does not need it: its own {@code availability}
   * guard already makes its query self-consuming, so a retry finds nothing left. The notify job
   * changes nothing the query reads, which is why it needs an explicit marker to converge against.
   *
   * @param windowStart inclusive lower bound on {@code approvedat}, and the lower bound on marker
   *     age
   * @param windowEnd inclusive upper bound on {@code approvedat}
   * @param afterSpId exclusive keyset cursor; {@code 0} for the first page
   * @param batchSize maximum ids to return
   * @return up to {@code batchSize} SP ids in ascending order, empty when the window is exhausted
   */
  @Transactional(readOnly = true)
  public List<Integer> findUnnotifiedEligibleSpIds(
      Timestamp windowStart, Timestamp windowEnd, Integer afterSpId, int batchSize) {
    return approvalRepository.findUnnotifiedEligibleSpIds(
        windowStart,
        windowEnd,
        SpKitApprovalActions.KIT_APPROVED_NOTIFIED,
        afterSpId,
        PageRequest.of(0, batchSize));
  }
}
