package com.yesmadam.schedulerservice.jobs.couponwhatsapp.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.yesmadam.persistence.coupon.CouponClaim.WaStatus;
import com.yesmadam.schedulerservice.jobs.couponwhatsapp.repository.CouponWhatsappCandidateRepository;

/**
 * Claims coupon-WhatsApp candidates as sent, in one batch UPDATE per call rather than one per
 * claim. Called only with claim ids that were actually published to SQS, so a mid-run retry that
 * re-fetches candidates does not see (and does not re-publish) anything this already marked.
 */
@Service
public class CouponWhatsappReminderWriter {

  private final CouponWhatsappCandidateRepository candidateRepository;

  public CouponWhatsappReminderWriter(CouponWhatsappCandidateRepository candidateRepository) {
    this.candidateRepository = candidateRepository;
  }

  @Transactional
  public int markSent(List<Long> claimIds) {
    if (CollectionUtils.isEmpty(claimIds)) {
      return 0;
    }
    return candidateRepository.markSent(claimIds, WaStatus.SENT.name(), WaStatus.PENDING.name());
  }
}
