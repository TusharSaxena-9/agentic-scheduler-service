package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.SapErrorLog;

@Repository("eliteMismatchSyncSapErrorLogRepository")
public interface SapErrorLogRepository extends JpaRepository<SapErrorLog, Integer> {}
