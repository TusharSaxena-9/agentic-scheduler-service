package com.yesmadam.schedulerservice.jobs.couponwhatsapp.service;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.persistence.coupon.CouponClaim.WaStatus;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.projections.CouponWhatsappCandidateProjection;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.repository.CouponWhatsappCandidateRepository;


@Service
public class CouponWhatsappReminderReader {


  private static final List<Integer> ELIGIBLE_ORDER_STATUS_CODES = List.of(5, 6);

  private final CouponWhatsappCandidateRepository candidateRepository;

  public CouponWhatsappReminderReader(CouponWhatsappCandidateRepository candidateRepository) {
    this.candidateRepository = candidateRepository;
  }

  @Transactional(readOnly = true)
  public List<CouponWhatsappCandidateProjection> fetchCandidates(Timestamp windowStart, Timestamp windowEnd) {
    return candidateRepository.findPendingCandidates(
        WaStatus.PENDING.name(), ELIGIBLE_ORDER_STATUS_CODES, windowStart, windowEnd);
  }
}
