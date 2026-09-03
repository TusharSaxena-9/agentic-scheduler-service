package com.yesmadam.schedulerservice.jobs.spwalletsum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.jobs.spwalletsum.model.SpSavingWallet;
import com.yesmadam.schedulerservice.jobs.spwalletsum.projections.SpWalletActiveDeactiveProjection;

/**
 * Read-only access to {@code tbl_sp_saving_wallet} for the wallet-sum snapshot. Same shape as
 * {@link SpMainWalletRepository} — see its javadoc for the access path and the rule-15 reasoning
 * behind bucketing active/deactive in one query instead of an id-list round trip.
 */
@Repository
public interface SpSavingWalletRepository extends JpaRepository<SpSavingWallet, Integer> {

  /** Sums {@code savingWalletAmount} across every SP's saving wallet, bucketed by SP status. */
  @Query(
      "SELECT "
          + "SUM(CASE WHEN sp.status = true THEN w.savingWalletAmount ELSE 0 END) AS activeSum, "
          + "SUM(CASE WHEN sp.status = false THEN w.savingWalletAmount ELSE 0 END) AS deactiveSum "
          + "FROM SpSavingWallet w, SPRegistration sp "
          + "WHERE w.spId = sp.id")
  SpWalletActiveDeactiveProjection sumSavingWalletBySpStatus();
}
