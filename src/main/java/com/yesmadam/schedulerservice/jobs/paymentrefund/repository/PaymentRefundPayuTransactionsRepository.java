package com.yesmadam.schedulerservice.jobs.paymentrefund.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.paymentrefund.model.PayuTransactionsEntity;

/** See {@link PayuTransactionsEntity} for why this is a local entity, not the shared jar's. */
@Repository
public interface PaymentRefundPayuTransactionsRepository
    extends JpaRepository<PayuTransactionsEntity, Long> {

  /**
   * A given {@code orderId} is not guaranteed to have only one row — a refund attempt itself
   * inserts a second, distinct audit row for the same {@code orderId} (see {@code
   * PaymentRefundOrderCreationService#attemptPayuRefund}). A plain {@code findByOrderId} that
   * returns the bare entity type throws {@code IncorrectResultSizeDataAccessException} the moment
   * a second row exists — which happens after the very first successful refund for that
   * transaction. {@code findFirst...OrderByIdDesc} instead deterministically picks the
   * most-recently-inserted row and never throws on multiples.
   */
  PayuTransactionsEntity findFirstByOrderIdOrderByIdDesc(String orderId);
}
