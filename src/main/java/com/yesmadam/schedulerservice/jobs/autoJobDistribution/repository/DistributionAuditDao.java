package com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.yesmadam.schedulerservice.common.constants.DBConstants;
import com.yesmadam.schedulerservice.jobs.autoJobDistribution.model.JobDistributionConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * Writes the {@code tbl_order_history} audit line for every distribution lifecycle transition.
 *
 * <p>This is the only human-readable record of why a round closed the way it did — the SAAB-349
 * debugging sessions were reconstructed almost entirely from these lines — so a failure to write
 * one is logged loudly but never allowed to fail the transition itself. Losing the audit line is
 * bad; leaving a round live because the audit write failed is worse.
 *
 * <p>Plain {@code JdbcTemplate} insert rather than an entity: {@code tbl_order_history} is wide and
 * shared across services, and this job only ever writes these four columns.
 */
@Slf4j
@Repository
public class DistributionAuditDao {

  private static final String INSERT_SQL =
      "INSERT INTO "
          + DBConstants.YESMADAM_USERS
          + ".tbl_order_history"
          + " (order_id, message, log_at, log_by) VALUES (?, ?, ?, ?)";

  private final JdbcTemplate jdbcTemplate;

  public DistributionAuditDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Records one lifecycle transition against the order. Never throws. */
  public void audit(Long orderId, String message) {
    try {
      jdbcTemplate.update(
          INSERT_SQL,
          orderId,
          message,
          new Timestamp(System.currentTimeMillis()),
          JobDistributionConstants.SYSTEM_LOG_BY);
    } catch (RuntimeException ex) {
      log.error(
          "event=distribution_audit_write_failed order_id={} message={} error={}",
          orderId,
          message,
          ex.getMessage(),
          ex);
    }
  }
}
