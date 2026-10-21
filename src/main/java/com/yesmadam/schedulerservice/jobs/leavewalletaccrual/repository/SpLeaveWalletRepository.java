package com.yesmadam.schedulerservice.jobs.leavewalletaccrual.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.leavewalletaccrual.model.SpLeaveWallet;

@Repository
public interface SpLeaveWalletRepository extends JpaRepository<SpLeaveWallet, Integer> {

  /** Every SP id with a leave wallet row, used to chunk the accrual write. */
  @Query("select s.spid from SpLeaveWallet s")
  List<Integer> findAllSpIds();

  /**
   * SP ids whose hub+category combination has SDL enabled (ANY working category counts). Read-only
   * and runs once, before any tbl_sp_leave_wallet row is touched — so it never holds a lock across
   * the write phase, and the busy, shared tbl_sp/tbl_sp_working_categories tables are never joined
   * inside a write transaction.
   */
  @Query(
      value =
          """
    SELECT DISTINCT swc.sp_id
    FROM ysmdm_admin.tbl_sp_working_categories swc
    JOIN ysmdm_admin.tbl_sp sp ON sp.id = swc.sp_id
    JOIN ysmdm_admin.tbl_qr_payment_hub_category_mapping m
      ON m.hub_id = sp.hub_id AND m.cat_id = swc.cat_id
    WHERE m.sdl_enable = 1
    """,
      nativeQuery = true)
  List<Integer> findSdlRestrictedSpIds();

  /**
   * Accrues one month of leave for one chunk of SPs. Scoped to {@code chunkSpIds} and touching only
   * tbl_sp_leave_wallet/tbl_sp_leave_wallet_internal, so each write transaction locks a bounded
   * batch instead of the whole table — {@code spaSpIds} (whether each SP gets the SDL-restricted
   * grant) is resolved once up front by {@link #findSdlRestrictedSpIds()}, not re-joined here.
   *
   * @return number of tbl_sp_leave_wallet rows updated in this chunk
   */
  @Modifying
  @Query(
      value =
          """
    UPDATE ysmdm_admin.tbl_sp_leave_wallet slw
    LEFT JOIN ysmdm_admin.tbl_sp_leave_wallet_internal slwi
        ON slw.spid = slwi.spId
       AND slwi.monthCode = MONTH(NOW())
       AND slwi.year = YEAR(NOW())
    SET slw.`leave` = GREATEST(
        (slw.`leave` + IF(slw.spid IN (:spaSpIds), :standardWoMonthly - :spaHydraSdlMonthly, :standardWoMonthly))
        - IFNULL(slwi.takenLeave, 0), 0)
    WHERE slw.spid IN (:chunkSpIds)
    """,
      nativeQuery = true)
  int accrueMonthlyLeaveChunk(
      @Param("chunkSpIds") List<Integer> chunkSpIds,
      @Param("spaSpIds") List<Integer> spaSpIds,
      @Param("standardWoMonthly") BigDecimal standardWoMonthly,
      @Param("spaHydraSdlMonthly") BigDecimal spaHydraSdlMonthly);
}
