package com.yesmadam.schedulerservice.jobs.userwalletsum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.User;
import com.yesmadam.schedulerservice.jobs.userwalletsum.projections.UserWalletSumProjection;

/**
 * Read-only access to {@code tbl_user} for the wallet-sum snapshot. Plain HQL — a two-column
 * {@code SUM()} over one table needs nothing native (CLAUDE.md rule 16).
 */
@Repository
public interface UserWalletSumRepository extends JpaRepository<User, Long> {

  /**
   * Sums {@code walletMin} (main wallet) and {@code amountLimit} (seamless wallet) across every
   * user, same as the messagingapis original — no batching or windowing.
   *
   * <p>Access path: full scan of {@code tbl_user} — there is no {@code WHERE} clause to be
   * index-backed, by design, since the semantics are "every user." Not chunkable for the same
   * reason: it is one aggregate, not a page-able set of rows (CLAUDE.md rule 15). {@code
   * startToCloseTimeout} on the calling activity's stub is sized generously as a placeholder for
   * this and should be revisited once a real duration has been observed on the actual table size.
   */
  @Query(
      "SELECT SUM(u.walletMin) AS mainWalletSum, SUM(u.amountLimit) AS seemlessWalletSum "
          + "FROM User u")
  UserWalletSumProjection sumAllWallets();
}
