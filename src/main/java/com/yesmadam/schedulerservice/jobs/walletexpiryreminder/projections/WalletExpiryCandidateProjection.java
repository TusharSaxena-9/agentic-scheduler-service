package com.yesmadam.schedulerservice.jobs.walletexpiryreminder.projections;

import java.sql.Timestamp;

/** One {@code tbl_user} row eligible for a wallet-expiry reminder. */
public interface WalletExpiryCandidateProjection {

  Long getUserId();

  Long getMobile();

  Integer getWalletMin();

  Integer getAmountLimit();

  Timestamp getExpireAt();
}
