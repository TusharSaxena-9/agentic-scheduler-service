package com.yesmadam.schedulerservice.jobs.userwalletsum.projections;

/** Result of the wallet-sum aggregate query over {@code tbl_user}. */
public interface UserWalletSumProjection {

  Long getMainWalletSum();

  Long getSeemlessWalletSum();
}
