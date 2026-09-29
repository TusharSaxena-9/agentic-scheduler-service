package com.yesmadam.schedulerservice.jobs.commissionaudit.repository;

import java.sql.Date;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Order;
import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.commissionaudit.projections.CommissionAuditProjection;

/**
 * Read-only access for the commission deduction audit. Nothing here writes.
 *
 * <p>Looks at orders booked on one date with {@code order_status_code} 5 ORDER_COMPLETED,
 * 6 ORDER_FEEDBACK_DONE or 8 ORDER_START_JOB. These are the statuses the 03:00 cron acts on: it
 * auto-completes a START_JOB order before deducting, and skips the deduction if that fails. Each
 * order gets the first reason below that applies:
 *
 * <ul>
 *   <li>{@code NO_SP_ASSIGNED}: {@code s_p_assign_by_user} is NULL or 0. empapi's
 *       {@code /cron/process-orders} returns {@code sp_not_found} for these, so no commission can be
 *       deducted.
 *   <li>{@code JOB_NOT_COMPLETED}: still at 8 ORDER_START_JOB and not commission done. The cron's
 *       auto-complete failed ({@code status_change_failed}), so no commission was deducted.
 *   <li>{@code NO_ORDER_SUPPORT_ROW}: there is no {@code tbl_order_support} row. empapi skips
 *       deduction when that row is missing.
 *   <li>{@code COMMISSION_NOT_DEDUCTED}: {@code payment_status_code} is not 3
 *       (PAYMENT_COMMISSION_DONE).
 *   <li>{@code COMMISSION_PASSBOOK_MISSING}: the order is marked deducted with {@code commission > 0},
 *       but it has no {@code tbl_sp_passbook} row with {@code reasonCode = 11} (COMMISSION). A
 *       zero-commission order is not flagged here, because {@code PassbookLineApplier} skips
 *       zero-amount lines and never writes a passbook row for it.
 * </ul>
 *
 * <p>Access path: {@code tbl_order} is filtered by an equality match on {@code booking_date}, one
 * day. {@code tbl_order_support} is joined on {@code order_id}. {@code tbl_sp_passbook} (~25M rows)
 * is read only for the day's commission-done orders, one {@code ref} lookup each on
 * {@code idx_tbl_sp_passbook_order_id}. No other passbook rows are touched, so the cost follows the
 * day's order count, not the size of the passbook table.
 */
@Repository
public interface CommissionAuditRepository extends JpaRepository<Order, Long> {

  /**
   * Every flagged order for the date, with no row limit. Rows are ordered by reason, then order id.
   *
   * @param bookingDate the booking date to audit
   */
  @Query(
      value =
          "WITH orders AS ("
              + "  SELECT o.order_id, o.s_p_assign_by_user, o.order_status_code,"
              + "         os.order_id AS os_order_id, os.payment_status_code, os.commission"
              + "  FROM "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order o"
              + "  LEFT JOIN "
              + DBConstants.YESMADAM_USERS
              + ".tbl_order_support os ON os.order_id = o.order_id"
              + "  WHERE o.booking_date = :bookingDate AND o.order_status_code IN (5, 6, 8)"
              + "), "
              // Only orders marked commission done with commission > 0 need the passbook check.
              + "passbook_done AS ("
              + "  SELECT DISTINCT pb.order_id"
              + "  FROM orders c"
              + "  JOIN "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_passbook pb ON pb.order_id = c.order_id AND pb.reasonCode = 11"
              + "  WHERE c.payment_status_code = 3 AND c.commission > 0"
              + ") "
              + "SELECT c.order_id AS orderId, c.s_p_assign_by_user AS spId,"
              + "       CASE"
              + "         WHEN c.s_p_assign_by_user IS NULL OR c.s_p_assign_by_user = 0"
              + "              THEN 'NO_SP_ASSIGNED'"
              + "         WHEN c.order_status_code = 8"
              + "              AND (c.payment_status_code IS NULL OR c.payment_status_code <> 3)"
              + "              THEN 'JOB_NOT_COMPLETED'"
              + "         WHEN c.os_order_id IS NULL"
              + "              THEN 'NO_ORDER_SUPPORT_ROW'"
              + "         WHEN c.payment_status_code IS NULL OR c.payment_status_code <> 3"
              + "              THEN 'COMMISSION_NOT_DEDUCTED'"
              + "         WHEN c.commission > 0 AND pd.order_id IS NULL"
              + "              THEN 'COMMISSION_PASSBOOK_MISSING'"
              + "       END AS reason "
              + "FROM orders c "
              + "LEFT JOIN passbook_done pd ON pd.order_id = c.order_id "
              + "HAVING reason IS NOT NULL "
              + "ORDER BY reason, orderId",
      nativeQuery = true)
  List<CommissionAuditProjection> findFlaggedOrders(@Param("bookingDate") Date bookingDate);
}
