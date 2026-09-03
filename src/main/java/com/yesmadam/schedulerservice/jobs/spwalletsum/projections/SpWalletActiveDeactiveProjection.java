package com.yesmadam.schedulerservice.jobs.spwalletsum.projections;

/**
 * Result of one wallet table's status-bucketed sum query: the active-SP total and the
 * deactive-SP total in a single row. Shared shape for both the main and saving wallet queries.
 */
public interface SpWalletActiveDeactiveProjection {

  Long getActiveSum();

  Long getDeactiveSum();
}
