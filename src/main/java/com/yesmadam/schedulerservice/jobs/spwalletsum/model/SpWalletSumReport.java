package com.yesmadam.schedulerservice.jobs.spwalletsum.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of one SP wallet-sum computation, as a plain serializable value.
 *
 * <p>Separate from the query projections because this crosses the Temporal activity boundary and
 * gets stored in workflow history — it has to be JSON-serializable, which a Hibernate projection
 * proxy is not.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpWalletSumReport {

  private Long mainActiveWalletSum;
  private Long savingActiveWalletSum;
  private Long mainDeActiveWalletSum;
  private Long savingDeActiveWalletSum;
}
