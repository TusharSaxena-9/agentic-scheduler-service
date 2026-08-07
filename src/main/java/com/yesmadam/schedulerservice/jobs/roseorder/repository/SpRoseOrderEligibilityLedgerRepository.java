package com.yesmadam.schedulerservice.jobs.roseorder.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SpRoseOrderEligibilityLedger;

@Repository
public interface SpRoseOrderEligibilityLedgerRepository
    extends JpaRepository<SpRoseOrderEligibilityLedger, Long> {}
