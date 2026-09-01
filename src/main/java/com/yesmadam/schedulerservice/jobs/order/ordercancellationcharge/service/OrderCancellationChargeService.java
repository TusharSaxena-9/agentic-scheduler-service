package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.service;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.enumu.CancellationChargeStatus;
import com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.repository.OrderCancellationChargeRepository;

/**
 * Business logic for the order cancellation charge expiry job: one write, no separate read step
 * to split it from, so a single service bean is enough here (unlike SebalTagging's reader/writer
 * split, which exists to keep a read and a write on separate proxy-crossing beans).
 *
 * <p>Idempotent by construction — the update's {@code WHERE} clause only matches rows still in
 * CREATED/CHARGED, so re-running after a partial failure (or a Temporal retry) simply finds fewer
 * or no rows the second time. There is no way to double-apply this write, so no marker row is
 * needed (CLAUDE.md rule 14). It is also a single SQL statement, so there is no partially-applied
 * state to recover from: it either commits in full or not at all.
 */
@Service
public class OrderCancellationChargeService {

  private static final List<CancellationChargeStatus> OVERDUE_STATUSES =
      List.of(CancellationChargeStatus.CREATED, CancellationChargeStatus.CHARGED);

  private final OrderCancellationChargeRepository repository;

  public OrderCancellationChargeService(OrderCancellationChargeRepository repository) {
    this.repository = repository;
  }

  /**
   * @param cutoff charges with {@code expiry_at} before this instant are expired
   * @return number of rows updated
   */
  @Transactional
  public int expireOverdue(Timestamp cutoff) {
    return repository.expireOverdue(CancellationChargeStatus.EXPIRED, OVERDUE_STATUSES, cutoff);
  }
}
