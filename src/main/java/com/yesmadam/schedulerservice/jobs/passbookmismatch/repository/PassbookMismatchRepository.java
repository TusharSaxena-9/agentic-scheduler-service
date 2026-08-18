package com.yesmadam.schedulerservice.jobs.passbookmismatch.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.SpPassbook;
import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.passbookmismatch.projections.PassbookMismatchProjection;

/**
 * Read-only access to {@code tbl_sp_passbook} for the mismatch audit. Nothing here writes — the
 * audit never touches the money rows it inspects.
 *
 * <p>The window bounds are parameters rather than {@code NOW()} inside the SQL, so the caller
 * decides the window once, records it, and can re-run the identical window on a retry.
 */
@Repository
public interface PassbookMismatchRepository extends JpaRepository<SpPassbook, Long> {

  /**
   * Every entry in the window that violates either passbook invariant, with the reason.
   *
   * <p>Per SP: in-window entries are chained against each other, and the earliest in-window entry
   * is chained against that SP's last entry before the window — however old that entry is.
   *
   * <p>Flagged when any of:
   *
   * <ul>
   *   <li>{@code OPENING_BALANCE_BREAK} — {@code openingbalance} != previous entry's {@code
   *       closingbalance}
   *   <li>{@code MOVEMENT_MISMATCH} — {@code closingbalance} != {@code openingbalance +/- amount}
   *   <li>{@code NULL_BALANCE_FIELDS} — a balance column is NULL, so nothing can be verified
   *   <li>{@code UNRESOLVED_ACTION_TYPE} — {@code actiontype} was neither CREDIT nor DEBIT
   * </ul>
   *
   * <p>{@code anchor_ids} is set-based (one join + one group by) rather than a correlated {@code
   * MAX()} per SP. Measured on stage (1.4M rows, 9,854 SPs) the correlated form cost ~0.80 ms per
   * SP and did not improve when the {@code (spid, id)} index was added, nor with {@code FORCE
   * INDEX} — the cost was per-execution overhead across thousands of separate subquery runs.
   *
   * @param windowStart inclusive lower bound on {@code date}
   * @param windowEnd exclusive upper bound on {@code date}
   */
  @Query(
      value =
          "WITH win AS ("
              + "  SELECT id, spid, `date`, openingbalance, amount, closingbalance, actiontype FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_passbook"
              + "  WHERE `date` >= :windowStart AND `date` < :windowEnd AND spid IS NOT NULL"
              + "), "
              + "firsts AS ("
              + "  SELECT spid, MIN(id) AS first_id FROM win GROUP BY spid"
              + "), "
              + "anchor_ids AS ("
              + "  SELECT prev.spid, MAX(prev.id) AS anchor_id FROM "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_passbook prev"
              + "  JOIN firsts f ON f.spid = prev.spid AND prev.id < f.first_id"
              + "  GROUP BY prev.spid"
              + "), "
              + "anchors AS ("
              + "  SELECT p.id, p.spid, p.`date`, p.openingbalance, p.amount, p.closingbalance,"
              + "         p.actiontype"
              + "  FROM anchor_ids a JOIN "
              + DBConstants.YESMADAM_ADMIN
              + ".tbl_sp_passbook p ON p.id = a.anchor_id"
              + "), "
              + "chained AS ("
              + "  SELECT u.id, u.spid, u.`date`, u.in_window, u.actiontype,"
              + "         u.openingbalance, u.amount, u.closingbalance,"
              + "         LAG(u.closingbalance) OVER (PARTITION BY u.spid ORDER BY u.id) AS prev_closing,"
              + "         CASE WHEN UPPER(TRIM(u.actiontype)) = 'CREDIT'"
              + "                   THEN u.openingbalance + u.amount"
              + "              WHEN UPPER(TRIM(u.actiontype)) = 'DEBIT'"
              + "                   THEN u.openingbalance - u.amount"
              + "         END AS expected_closing"
              + "  FROM ("
              + "    SELECT id, spid, `date`, openingbalance, amount, closingbalance, actiontype,"
              + "           1 AS in_window FROM win"
              + "    UNION ALL"
              + "    SELECT id, spid, `date`, openingbalance, amount, closingbalance, actiontype,"
              + "           0 AS in_window FROM anchors"
              + "  ) u"
              + "), "
              + "flagged AS ("
              + "  SELECT c.*, CONCAT_WS(' + ',"
              + "    CASE WHEN c.openingbalance IS NULL OR c.amount IS NULL"
              + "              OR c.closingbalance IS NULL"
              + "         THEN 'NULL_BALANCE_FIELDS' END,"
              + "    CASE WHEN c.openingbalance IS NOT NULL AND c.amount IS NOT NULL"
              + "              AND c.closingbalance IS NOT NULL AND c.expected_closing IS NULL"
              + "         THEN 'UNRESOLVED_ACTION_TYPE' END,"
              + "    CASE WHEN c.prev_closing IS NOT NULL"
              + "              AND c.openingbalance <> c.prev_closing"
              + "         THEN 'OPENING_BALANCE_BREAK' END,"
              + "    CASE WHEN c.expected_closing IS NOT NULL"
              + "              AND c.expected_closing <> c.closingbalance"
              + "         THEN 'MOVEMENT_MISMATCH' END"
              + "  ) AS mismatch_reason FROM chained c"
              + ") "
              + "SELECT id AS passbookId, spid AS spId, `date` AS passbookDate,"
              + "       mismatch_reason AS mismatchReason, openingbalance AS openingBalance,"
              + "       amount AS amount, closingbalance AS closingBalance,"
              + "       actiontype AS actionType, prev_closing AS prevClosing,"
              + "       expected_closing AS expectedClosing "
              + "FROM flagged "
              + "WHERE in_window = 1 AND mismatch_reason <> '' "
              + "ORDER BY id",
      nativeQuery = true)
  List<PassbookMismatchProjection> findMismatches(
      @Param("windowStart") Timestamp windowStart, @Param("windowEnd") Timestamp windowEnd);
}
