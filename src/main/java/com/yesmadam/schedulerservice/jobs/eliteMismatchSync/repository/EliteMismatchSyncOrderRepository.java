package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncOrder;

/** Mirrors tracking's {@code UtilitiesDao#getOrdersWithEliteFees}. */
@Repository
public interface EliteMismatchSyncOrderRepository extends JpaRepository<EliteMismatchSyncOrder, Long> {

  List<EliteMismatchSyncOrder> findByUseridAndEliteFeesIsNotNull(Integer userid);
}
