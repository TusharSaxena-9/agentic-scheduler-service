package com.yesmadam.schedulerservice.config;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Configures separate writer (primary) and reader (replica) connection pools and exposes a single
 * routing datasource as the application's primary {@link DataSource}.
 *
 * <p>Routing is decided per transaction by {@link ReadWriteRoutingDataSource}: read-only
 * transactions go to the reader, everything else goes to the writer.
 *
 * <p>Pool properties bind from {@code spring.datasource.writer.*} and {@code
 * spring.datasource.reader.*} (Hikari property names, e.g. {@code jdbc-url}, {@code username},
 * {@code password}, {@code maximum-pool-size}).
 */
@Configuration
public class DataSourceConfig {

  /**
   * Creates the writer (primary) connection pool.
   *
   * @return the writer HikariCP datasource
   */
  @Bean
  @ConfigurationProperties("spring.datasource.writer")
  public HikariDataSource writerDataSource() {
    return new HikariDataSource();
  }

  /**
   * Creates the reader (replica) connection pool.
   *
   * @return the reader HikariCP datasource
   */
  @Bean
  @ConfigurationProperties("spring.datasource.reader")
  public HikariDataSource readerDataSource() {
    return new HikariDataSource();
  }

  /**
   * Exposes the routing datasource used by JPA/Hibernate.
   *
   * <p>The {@link LazyConnectionDataSourceProxy} wrapper is required for correct routing: it defers
   * fetching a physical connection until the first statement executes, by which point the
   * transaction's read-only flag is visible to the router.
   *
   * @param writerDataSource the writer pool
   * @param readerDataSource the reader pool
   * @return the primary routing datasource
   */
  @Bean
  @Primary
  public DataSource dataSource(
      @Qualifier("writerDataSource") DataSource writerDataSource,
      @Qualifier("readerDataSource") DataSource readerDataSource) {
    ReadWriteRoutingDataSource routingDataSource = new ReadWriteRoutingDataSource();

    Map<Object, Object> targetDataSources = new HashMap<>();
    targetDataSources.put(DataSourceType.WRITER, writerDataSource);
    targetDataSources.put(DataSourceType.READER, readerDataSource);
    routingDataSource.setTargetDataSources(targetDataSources);
    routingDataSource.setDefaultTargetDataSource(writerDataSource);
    routingDataSource.afterPropertiesSet();

    return new LazyConnectionDataSourceProxy(routingDataSource);
  }
}
