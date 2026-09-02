package com.yesmadam.schedulerservice.jobs.userwalletsum.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of one wallet-sum computation, as a plain serializable value.
 *
 * <p>Separate from the query projection because this crosses the Temporal activity boundary and
 * gets stored in workflow history — it has to be JSON-serializable, which a Hibernate projection
 * proxy is not.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserWalletSumReport {

  private Long mainWalletSum;
  private Long seemlessWalletSum;
}
