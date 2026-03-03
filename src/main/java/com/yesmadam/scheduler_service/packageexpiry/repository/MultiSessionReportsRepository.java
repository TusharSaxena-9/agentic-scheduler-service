package com.yesmadam.scheduler_service.packageexpiry.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.scheduler_service.packageexpiry.model.MultiSessionReports;

@Repository
public interface MultiSessionReportsRepository extends JpaRepository<MultiSessionReports, Long> {}
