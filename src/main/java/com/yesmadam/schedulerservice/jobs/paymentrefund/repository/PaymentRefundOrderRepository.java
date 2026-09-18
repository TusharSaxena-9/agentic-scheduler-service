package com.yesmadam.schedulerservice.jobs.paymentrefund.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;

/**
 * Wraps the shared {@code ym-common-entity} {@link Order} entity (already registered elsewhere in
 * this service, e.g. {@code SpRepository}'s JPQL). Its {@code payment_source_id}/{@code
 * payment_source} fields contain underscores, which a derived Spring Data method name can't address
 * reliably — an explicit JPQL query (CLAUDE.md rule 16) avoids that ambiguity.
 */
@Repository
public interface PaymentRefundOrderRepository extends JpaRepository<Order, Long> {

  @Query(
      "select count(o) > 0 from Order o "
          + "where o.payment_source_id = :paymentSourceId and o.payment_source in :paymentSources")
  boolean existsByPaymentSourceIdAndPaymentSourceIn(
      @Param("paymentSourceId") String paymentSourceId,
      @Param("paymentSources") List<String> paymentSources);
}
