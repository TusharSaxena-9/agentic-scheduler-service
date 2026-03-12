package com.yesmadam.scheduler_service.crontiming.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.scheduler_service.crontiming.model.CronTiming;

@Repository
public interface CronTimingRepository extends JpaRepository<CronTiming, Long> {}
