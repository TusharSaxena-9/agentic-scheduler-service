package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.yesmadam.persistence.User;
import com.yesmadam.schedulerservice.jobs.walletexpiryreminder.projections.WalletExpiryCandidateProjection;


@Repository
public interface WalletExpiryCandidateRepository extends JpaRepository<User, Long> {

  @Query(
      "SELECT u.user_id AS userId, u.mobile AS mobile, u.walletMin AS walletMin, "
          + "u.amountLimit AS amountLimit, u.expireAt AS expireAt "
          + "FROM User u "
          + "WHERE u.expireAt >= :dayStart AND u.expireAt < :dayEnd "
          + "AND u.walletMin > 0 "
          + "AND u.mobile IS NOT NULL "
          + "AND (u.isBlock IS NULL OR u.isBlock = false) "
          + "AND (u.isMessageBlock IS NULL OR u.isMessageBlock = false) "
          + "AND (u.whatsAppOptIn IS NULL OR u.whatsAppOptIn = true)")
  List<WalletExpiryCandidateProjection> findCandidates(
      @Param("dayStart") Timestamp dayStart, @Param("dayEnd") Timestamp dayEnd);
}
