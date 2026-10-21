package com.yesmadam.schedulerservice.config;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
public class ActivityDbConfig {

  @Bean
  @ConfigurationProperties("spring.datasource.activityevents")
  public HikariDataSource activityEventsDataSource() {
    return new HikariDataSource();
  }

  @Bean
  public ActivityDb activityDb(
      @Qualifier("activityEventsDataSource") HikariDataSource dataSource,
      @Value("${scheduler.activity-db.transaction-timeout-seconds:60}") int transactionTimeoutSeconds)
      throws SQLException {
    Assert.hasText(
        dataSource.getJdbcUrl(), "spring.datasource.activityevents.jdbc-url is not set");
    Assert.hasText(
        dataSource.getUsername(), "spring.datasource.activityevents.username is not set");
    Assert.hasText(
        dataSource.getPassword(), "spring.datasource.activityevents.password is not set");
    try (Connection ignored = dataSource.getConnection()) {
    }
    return new ActivityDb(dataSource, transactionTimeoutSeconds);
  }
}
