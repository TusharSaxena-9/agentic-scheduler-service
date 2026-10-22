package com.yesmadam.schedulerservice.jobs.cinntrapassbook.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.cinntrapassbook.model.CinntraPassbookSapDocEntry;

/**
 * Appends {@code tbl_sap_doc_entries} rows on a successful SAP post. {@code save} runs on the
 * writer.
 */
@Repository
public interface CinntraPassbookSapDocEntryRepository
    extends JpaRepository<CinntraPassbookSapDocEntry, Integer> {}
