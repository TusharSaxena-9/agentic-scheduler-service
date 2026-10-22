package com.yesmadam.schedulerservice.jobs.cinntraPassbook.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.cinntraPassbook.model.CinntraPassbookSapErrorLog;

/** Appends {@code tbl_sap_error_log} rows on a failed SAP post. {@code save} runs on the writer. */
@Repository
public interface CinntraPassbookSapErrorLogRepository extends JpaRepository<CinntraPassbookSapErrorLog, Integer> {}
