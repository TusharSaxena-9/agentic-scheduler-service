package com.yesmadam.schedulerservice.jobs.eliteMismatchSync.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.eliteMismatchSync.model.EliteMismatchSyncSubscription;

@Repository
public interface EliteMismatchSyncSubscriptionRepository
    extends JpaRepository<EliteMismatchSyncSubscription, Integer> {

  /**
   * Mirrors tracking's {@code UtilitiesDao#getSubscriptionOrderIdsNotInSapDoc} exactly — a
   * Java-computed "yesterday" window (bind params, not DB-side {@code CURRENT_DATE}), joined
   * across {@code tbl_customer_subscriptions_elite} (ysmdm_users), {@code tbl_user_address}
   * (ysmdm_users) and {@code tbl_hub} (ysmdm_admin) — a cross-catalog join, so this must stay a
   * native query rather than JPQL. Excludes subscriptions that already have a {@code
   * tbl_sap_doc_entries} row. The address/hub joins are {@code LEFT JOIN}s (as in tracking), so
   * a customer with no address is still selected and invoiced from the default hub.
   *
   * <p>Read-only: a {@code @Query} does not inherit Spring Data's read-only default, so without
   * this it would run on the writer (CLAUDE.md rule 13).
   */
  @Transactional(readOnly = true)
  @Query(
      value =
          "SELECT cs.id "
              + "FROM " + DBConstants.YESMADAM_USERS + ".tbl_customer_subscriptions_elite cs "
              // LEFT JOINs: a customer without an address is still invoiced — the service falls
              // back to the default invoicing hub for them.
              + "LEFT JOIN " + DBConstants.YESMADAM_USERS + ".tbl_user_address ua ON cs.customerId = ua.user_id "
              + "LEFT JOIN " + DBConstants.YESMADAM_ADMIN + ".tbl_hub h ON ua.hub_id = h.id "
              + "LEFT JOIN " + DBConstants.YESMADAM_ADMIN + ".tbl_sap_doc_entries sd ON sd.orderId = cs.id "
              + "WHERE cs.createdAt >= :startDate "
              + "AND cs.createdAt < :endDate "
              + "AND sd.orderId IS NULL "
              + "GROUP BY cs.id "
              + "ORDER BY cs.createdAt DESC",
      nativeQuery = true)
  List<Integer> findSubscriptionIdsNotInSapDoc(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
}
