package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.repository;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;

import com.yesmadam.schedulerservice.config.ActivityDb;

@Repository
public class UserActivityScoreDecayDao {

  private static final String TABLE_NAME = "tbl_user_activity_service_score";

  private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z0-9_]+");

  private static final String DECAY_DUE_BATCH_SQL_TEMPLATE =
      "UPDATE %s "
          + "SET current_score = current_score "
          + "      * (CASE LEAST(DATEDIFF(CURDATE(), last_activity_at), 9) "
          + "              WHEN 0 THEN :d0 WHEN 1 THEN :d1 WHEN 2 THEN :d2 WHEN 3 THEN :d3 "
          + "              WHEN 4 THEN :d4 WHEN 5 THEN :d5 WHEN 6 THEN :d6 WHEN 7 THEN :d7 "
          + "              WHEN 8 THEN :d8 ELSE :d8Plus END) "
          + "      / (CASE LEAST(GREATEST(DATEDIFF(COALESCE(last_decayed_date, last_activity_at), last_activity_at), 0), 9) "
          + "              WHEN 0 THEN :d0 WHEN 1 THEN :d1 WHEN 2 THEN :d2 WHEN 3 THEN :d3 "
          + "              WHEN 4 THEN :d4 WHEN 5 THEN :d5 WHEN 6 THEN :d6 WHEN 7 THEN :d7 "
          + "              WHEN 8 THEN :d8 ELSE :d8Plus END), "
          + "    last_decayed_date = CURDATE(), "
          + "    next_decay_date = CASE WHEN DATEDIFF(CURDATE(), last_activity_at) >= 9 THEN NULL "
          + "                           ELSE DATE_ADD(CURDATE(), INTERVAL 1 DAY) END "
          + "WHERE next_decay_date <= CURDATE() "
          + "  AND (suppressed_until IS NULL OR suppressed_until <= CURDATE()) "
          + "ORDER BY next_decay_date, id "
          + "LIMIT :batchSize";

  private static final String CREDIT_EXPIRED_SUPPRESSIONS_SQL_TEMPLATE =
      "UPDATE %s "
          + "SET current_score = current_score + 50, "
          + "    suppressed_until = NULL "
          + "WHERE suppressed_until = CURDATE() "
          + "ORDER BY id "
          + "LIMIT :batchSize";

  private final ActivityDb activityDb;
  private final String decayDueBatchSql;
  private final String creditExpiredSuppressionsSql;

  public UserActivityScoreDecayDao(
      ActivityDb activityDb, @Value("${scheduler.activity-db.schema}") String schema) {
    Assert.hasText(schema, "scheduler.activity-db.schema is not set");
    Assert.isTrue(
        SCHEMA_NAME.matcher(schema).matches(),
        "scheduler.activity-db.schema must contain only letters, digits and underscores");
    String table = schema + "." + TABLE_NAME;
    this.activityDb = activityDb;
    this.decayDueBatchSql = String.format(DECAY_DUE_BATCH_SQL_TEMPLATE, table);
    this.creditExpiredSuppressionsSql = String.format(CREDIT_EXPIRED_SUPPRESSIONS_SQL_TEMPLATE, table);
  }

  public int decayDueBatch(
      double d0,
      double d1,
      double d2,
      double d3,
      double d4,
      double d5,
      double d6,
      double d7,
      double d8,
      double d8Plus,
      int batchSize) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("d0", d0)
            .addValue("d1", d1)
            .addValue("d2", d2)
            .addValue("d3", d3)
            .addValue("d4", d4)
            .addValue("d5", d5)
            .addValue("d6", d6)
            .addValue("d7", d7)
            .addValue("d8", d8)
            .addValue("d8Plus", d8Plus)
            .addValue("batchSize", batchSize);
    return activityDb.write(jdbc -> jdbc.update(decayDueBatchSql, params));
  }

  public int creditExpiredSuppressions(int batchSize) {
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("batchSize", batchSize);
    return activityDb.write(jdbc -> jdbc.update(creditExpiredSuppressionsSql, params));
  }
}
