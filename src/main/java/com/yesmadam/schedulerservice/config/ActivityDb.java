package com.yesmadam.schedulerservice.config;

import java.util.function.Function;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ActivityDb {

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate readTx;
  private final TransactionTemplate writeTx;

  public ActivityDb(DataSource dataSource, int transactionTimeoutSeconds) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);

    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
    this.readTx.setTimeout(transactionTimeoutSeconds);

    this.writeTx = new TransactionTemplate(transactionManager);
    this.writeTx.setTimeout(transactionTimeoutSeconds);
  }

  public <T> T read(Function<NamedParameterJdbcOperations, T> work) {
    return readTx.execute(status -> work.apply(jdbc));
  }

  public <T> T write(Function<NamedParameterJdbcOperations, T> work) {
    return writeTx.execute(status -> work.apply(jdbc));
  }
}
