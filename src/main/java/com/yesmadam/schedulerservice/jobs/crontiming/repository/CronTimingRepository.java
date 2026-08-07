package com.yesmadam.schedulerservice.jobs.crontiming.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.crontiming.model.CronTiming;

@Repository
public interface CronTimingRepository extends JpaRepository<CronTiming, Long> {}
