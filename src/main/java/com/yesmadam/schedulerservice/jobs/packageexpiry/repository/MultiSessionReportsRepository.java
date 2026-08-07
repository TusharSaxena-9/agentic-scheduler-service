package com.yesmadam.schedulerservice.jobs.packageexpiry.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.MultiSessionReports;

@Repository
public interface MultiSessionReportsRepository extends JpaRepository<MultiSessionReports, Long> {}
