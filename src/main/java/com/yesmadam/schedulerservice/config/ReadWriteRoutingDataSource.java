package com.yesmadam.schedulerservice.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes connections to the reader or writer datasource based on the current transaction's
 * read-only flag.
 *
 * <p>Transactions marked {@code @Transactional(readOnly = true)} are served by the reader
 * (replica); everything else — including code running outside a transaction — is served by the
 * writer. Spring Data JPA marks all repository find methods read-only by default, so plain
 * repository reads route to the reader automatically.
 *
 * <p>This datasource must be wrapped in a {@link
 * org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy}: the transaction manager
 * begins the transaction before the read-only flag becomes visible to {@link
 * TransactionSynchronizationManager}, so the physical connection lookup has to be deferred until
 * the first statement runs.
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

  @Override
  protected Object determineCurrentLookupKey() {
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ? DataSourceType.READER
        : DataSourceType.WRITER;
  }
}
