package com.yesmadam.schedulerservice.jobs.order.ordercancellationcharge.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.enumu.CancellationChargeStatus;
import com.yesmadam.persistence.OrderCancellationCharge;

@Repository
public interface OrderCancellationChargeRepository
    extends JpaRepository<OrderCancellationCharge, Long> {

  /**
   * Flags every overdue charge as EXPIRED in one statement, rather than fetching rows and saving
   * them back one at a time.
   *
   * <p>Access path: {@code idx_status} (single-column index on {@code status}) narrows to the
   * {@code overdueStatuses}, then {@code expiry_at} is filtered per matched row — there is no
   * index on {@code expiry_at}. Measured on UAT: 605 rows total, 128 in CREATED/CHARGED, so this is
   * a trivial scan today. Re-check this query if the table grows into the tens of thousands, since
   * the second filter isn't index-backed.
   *
   * @param expiredStatus the status to set matched rows to
   * @param overdueStatuses statuses eligible to expire
   * @param cutoff rows with {@code expiry_at} before this instant are matched
   * @return number of rows updated
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE OrderCancellationCharge c SET c.status = :expiredStatus, c.updatedAt = :cutoff "
          + "WHERE c.status IN :overdueStatuses AND c.expiryAt < :cutoff")
  int expireOverdue(
      @Param("expiredStatus") CancellationChargeStatus expiredStatus,
      @Param("overdueStatuses") List<CancellationChargeStatus> overdueStatuses,
      @Param("cutoff") Timestamp cutoff);
}
