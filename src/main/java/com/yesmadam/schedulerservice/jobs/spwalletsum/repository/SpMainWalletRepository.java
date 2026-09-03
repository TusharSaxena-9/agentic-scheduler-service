package com.yesmadam.schedulerservice.jobs.spwalletsum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.Spwallet;
import com.yesmadam.schedulerservice.jobs.spwalletsum.projections.SpWalletActiveDeactiveProjection;

/**
 * Read-only access to {@code tbl_sp_wallet} for the wallet-sum snapshot. Plain HQL — a status-
 * bucketed {@code SUM()} joined to one other table needs nothing native (CLAUDE.md rule 16).
 */
@Repository
public interface SpMainWalletRepository extends JpaRepository<Spwallet, Integer> {

  /**
   * Sums {@code walletAmount} across every SP's main wallet, split into active-SP and deactive-SP
   * totals by one {@code CASE}-bucketed aggregate.
   *
   * <p>Access path: full scan of {@code tbl_sp_wallet} theta-joined to {@code tbl_sp} on {@code
   * spId = id} — there is no {@code WHERE} clause narrowing either side, by design, since the
   * semantics are "every SP's wallet, bucketed by current status" (CLAUDE.md rule 15). This
   * replaces the messagingapis original's two separate queries keyed off an active/deactive SP id
   * list with one aggregate query and no id list at all — the original pattern of building an id
   * list and feeding it into a follow-up {@code IN (...)} query is exactly what rule 15 warns
   * against. The {@code CASE ... ELSE 0} also makes the original's "no deactive SPs" branch
   * unnecessary: an empty bucket just contributes zero. {@code startToCloseTimeout} on the calling
   * activity's stub is sized generously as a placeholder for this and should be revisited once a
   * real duration has been observed on the actual table size.
   */
  @Query(
      "SELECT "
          + "SUM(CASE WHEN sp.status = true THEN w.walletAmount ELSE 0 END) AS activeSum, "
          + "SUM(CASE WHEN sp.status = false THEN w.walletAmount ELSE 0 END) AS deactiveSum "
          + "FROM Spwallet w, SPRegistration sp "
          + "WHERE w.spId = sp.id")
  SpWalletActiveDeactiveProjection sumMainWalletBySpStatus();
}
