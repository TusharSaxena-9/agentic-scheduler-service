package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSapSeries;

/**
 * Mirrors tracking's {@code UtilitiesDao#getSeriesbyWhsCode} — a plain Criteria {@code
 * uniqueResult()} with no {@code setMaxResults}, so this genuinely throws if more than one row
 * shares a warehouse code. An {@code Optional<T>}-returning JPQL query reproduces that same
 * throw-on-non-unique behavior.
 */
@Repository
public interface EliteMismatchSyncSapSeriesRepository extends JpaRepository<EliteMismatchSyncSapSeries, Integer> {

  @Query("SELECT s FROM EliteMismatchSyncSapSeries s WHERE s.sapWhsCode = :sapWhsCode")
  Optional<EliteMismatchSyncSapSeries> findBySapWhsCode(@Param("sapWhsCode") String sapWhsCode);
}
