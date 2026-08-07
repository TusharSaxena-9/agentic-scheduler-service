package com.yesmadam.schedulerservice.jobs.sebaltagging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SebalTaggingWatermark;

/** Repository for the singleton watermark row ({@code tbl_sebal_tagging_watermark}, id = 1). */
@Repository
public interface SebalTaggingWatermarkRepository
    extends JpaRepository<SebalTaggingWatermark, Long> {}
