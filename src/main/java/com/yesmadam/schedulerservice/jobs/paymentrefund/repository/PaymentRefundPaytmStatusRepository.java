package com.yesmadam.schedulerservice.jobs.paymentrefund.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.PaytmStatusResponse;

/**
 * Wraps the shared {@code ym-common-entity} {@link PaytmStatusResponse} entity (maps {@code
 * tbl_paytm_payment_status}) — field-for-field match with {@code shortlink}'s local copy, including
 * the all-caps field names that mirror Paytm's own response JSON.
 */
@Repository
public interface PaymentRefundPaytmStatusRepository
    extends JpaRepository<PaytmStatusResponse, Long> {

  List<PaytmStatusResponse> findByORDERID(String orderId);
}
