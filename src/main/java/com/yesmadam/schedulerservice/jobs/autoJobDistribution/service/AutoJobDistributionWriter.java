package com.yesmadam.schedulerservice.jobs.autoJobDistribution.service;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistribution;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionStatus;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionPendingQueueBatchDao;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.DistributionPendingQueueRepository;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository.JobDistributionRepository;

/**
 * Every write the distribution engine performs, on its own bean.
 *
 * <p>Paired with {@link AutoJobDistributionReader} for the reason in rule 13:
 * {@code @Transactional} comes from a Spring proxy, and a call between two methods on the same bean
 * never reaches it. These writes previously sat on {@code AutoJobDistributionService} and were
 * invoked from its own methods, so they committed outside any declared transaction. Splitting them
 * out makes each one a real transaction boundary, and makes the read/write shape of this job
 * visible in its package listing.
 *
 * <p><b>Each method is the smallest unit that must commit together</b> — never the whole pass. That
 * matters here beyond style: a transaction holds a connection for its entire life, the pod has 30
 * writer connections against 166 activity slots, and this job runs every five minutes. A
 * transaction spanning a pass would also be wrong for retries: rule 14 wants the transaction
 * boundary to match the activity's retry boundary, and one spanning several steps cannot roll back
 * to match a retry of one of them.
 *
 * <p>Nothing here computes, sorts, formats or calls out over HTTP. All of that stays in the
 * service, outside any transaction — an outbound call inside one pins a connection for the length
 * of someone else's timeout.
 */
@Service
public class AutoJobDistributionWriter {

  private final JobDistributionRepository jobDistributionRepository;
  private final DistributionPendingQueueRepository pendingQueueRepository;
  private final DistributionPendingQueueBatchDao pendingQueueBatchDao;

  public AutoJobDistributionWriter(
      JobDistributionRepository jobDistributionRepository,
      DistributionPendingQueueRepository pendingQueueRepository,
      DistributionPendingQueueBatchDao pendingQueueBatchDao) {
    this.jobDistributionRepository = jobDistributionRepository;
    this.pendingQueueRepository = pendingQueueRepository;
    this.pendingQueueBatchDao = pendingQueueBatchDao;
  }

  /**
   * Adds orders to the waiting pool; already-waiting orders are skipped by the unique key.
   *
   * <p><b>Access path:</b> {@code uq_pending_order_active} on {@code
   * tbl_distribution_pending_queue} — the {@code INSERT IGNORE} collides against that unique index,
   * which covers the stored generated column {@code pending_order_id} (= {@code order_id} while
   * waiting, NULL otherwise). One multi-row statement, so one index probe per row and one round
   * trip for the batch.
   *
   * <p>Safe to repeat: that is what makes a Temporal retry of the trigger activity a no-op rather
   * than a double-queue (rule 14).
   */
  @Transactional
  public int enqueuePending(List<Long> orderIds) {
    return pendingQueueBatchDao.enqueuePending(orderIds);
  }

  /**
   * Closes a round with a terminal status and resolves its queue row.
   *
   * <p>These two statements genuinely belong in one transaction: a round closed without its queue
   * row resolved leaves the order looking as though it is still mid-flight, which is a value the
   * next pass acts on. That is rule 13's "match the guarantee to the consequence" — this is not an
   * append-only audit line.
   *
   * <p><b>Access path:</b> {@code PRIMARY} on {@code tbl_job_distribution} for the round update; on
   * {@code tbl_distribution_pending_queue} the order-scoped update filters {@code order_id = ? AND
   * is_pending IS NULL}, which has no index of its own and scans the small waiting table.
   *
   * <p>Every close sets {@code is_active = NULL}, never 0 — {@code uq_order_active (order_id,
   * is_active)} enforces one live round per order, and MySQL only treats NULL as distinct.
   */
  @Transactional
  public void close(
      JobDistribution round, JobDistributionStatus status, Integer spId, Integer incentive) {
    Timestamp now = new Timestamp(System.currentTimeMillis());
    if (status == JobDistributionStatus.ACCEPTED) {
      jobDistributionRepository.closeAsAccepted(round.getId(), spId, incentive, now);
    } else {
      jobDistributionRepository.closeWithStatus(round.getId(), status.name(), now);
    }
    pendingQueueRepository.markPendingResolved(round.getOrderId(), now);
  }

  /**
   * Discards a stale waiting row that never became active — {@code is_pending} 1 to 0.
   *
   * <p>Id-scoped on purpose: a re-queued order legitimately has several historical rows, and only
   * the claimed one may be touched.
   *
   * <p><b>Access path:</b> {@code PRIMARY} on {@code tbl_distribution_pending_queue}.
   */
  @Transactional
  public void discardPending(Long queueRowId) {
    pendingQueueRepository.clearPending(queueRowId, new Timestamp(System.currentTimeMillis()));
  }

  /**
   * Marks a waiting row as fired — {@code is_pending} 1 to NULL, stamping {@code distributed_at}.
   *
   * <p>Called only after a confirmed successful POST to admin. If the process dies between the fire
   * and this write, the row stays pending and is retried next pass — safe, because admin's own
   * {@code uq_order_active} dedupes a duplicate fire for the same order into a no-op. That
   * asymmetry is deliberate: re-firing is recoverable, marking a row fired that never fired is not.
   *
   * <p><b>Access path:</b> {@code PRIMARY} on {@code tbl_distribution_pending_queue}.
   */
  @Transactional
  public void markPendingAsDistributed(Long queueRowId) {
    pendingQueueRepository.clearPendingAsDistributed(
        queueRowId, new Timestamp(System.currentTimeMillis()));
  }
}
