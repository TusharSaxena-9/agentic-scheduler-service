package com.yesmadam.schedulerservice.jobs.paymentrefund.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.UserOTP;

/** Wraps the shared {@code ym-common-entity} {@link UserOTP} entity — full field match. */
@Repository
public interface PaymentRefundUserOtpRepository extends JpaRepository<UserOTP, Long> {}
