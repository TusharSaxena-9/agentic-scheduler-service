package com.yesmadam.schedulerservice.jobs.paymentrefund.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.User;

/**
 * Wraps the shared {@code ym-common-entity} {@link User} entity (maps {@code tbl_user}) — only
 * {@code findById} is needed here, to read a customer's mobile number for the OTP bypass.
 */
@Repository
public interface PaymentRefundUserRepository extends JpaRepository<User, Long> {}
