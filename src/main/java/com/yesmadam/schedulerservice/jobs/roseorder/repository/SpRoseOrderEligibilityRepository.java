package com.yesmadam.schedulerservice.jobs.roseorder.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SpRoseOrderEligibility;

/**
 * Repository for the current-state Rose Order eligibility table ({@code
 * sp_rose_order_eligibilities}).
 */
@Repository
public interface SpRoseOrderEligibilityRepository
    extends JpaRepository<SpRoseOrderEligibility, Long> {

  /**
   * Loads the current eligibility rows for the given SP ids (for upsert).
   *
   * @param spIds SP ids in the current chunk
   * @return existing eligibility rows; empty if none of the SPs have been evaluated before
   */
  List<SpRoseOrderEligibility> findBySp_IdIn(List<Integer> spIds);
}
