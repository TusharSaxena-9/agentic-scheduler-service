package com.yesmadam.schedulerservice.jobs.useractivityscoredecay.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.yesmadam.schedulerservice.config.ActivityDb;

@ExtendWith(MockitoExtension.class)
class UserActivityScoreDecayDaoTest {

  @Mock ActivityDb activityDb;
  @Mock NamedParameterJdbcOperations jdbc;

  private UserActivityScoreDecayDao dao;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dao = new UserActivityScoreDecayDao(activityDb, "ysmdm_admin");
    lenient().when(activityDb.write(any()))
        .thenAnswer(inv -> ((Function<NamedParameterJdbcOperations, Object>) inv.getArgument(0)).apply(jdbc));
  }

  @Test
  void decayDueBatch_shouldRunOneWriteWithEveryMultiplierAndTheBatchSize_andReturnRowsUpdated() {
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(137);

    int updated = dao.decayDueBatch(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 10_000);

    assertThat(updated).isEqualTo(137);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(sql.capture(), params.capture());

    assertThat(sql.getValue())
        .contains("UPDATE ysmdm_admin.tbl_user_activity_service_score ")
        .contains("WHERE next_decay_date <= CURDATE()")
        .contains("ORDER BY next_decay_date, id")
        .contains("LIMIT :batchSize");
    SqlParameterSource p = params.getValue();
    assertThat(p.getValue("d0")).isEqualTo(1.0);
    assertThat(p.getValue("d1")).isEqualTo(0.9);
    assertThat(p.getValue("d2")).isEqualTo(0.8);
    assertThat(p.getValue("d3")).isEqualTo(0.7);
    assertThat(p.getValue("d4")).isEqualTo(0.6);
    assertThat(p.getValue("d5")).isEqualTo(0.5);
    assertThat(p.getValue("d6")).isEqualTo(0.4);
    assertThat(p.getValue("d7")).isEqualTo(0.3);
    assertThat(p.getValue("d8")).isEqualTo(0.2);
    assertThat(p.getValue("d8Plus")).isEqualTo(0.1);
    assertThat(p.getValue("batchSize")).isEqualTo(10_000);
  }

  @Test
  void decayDueBatch_shouldDivideByTheMultiplierAlreadyAppliedNotYesterdays() {
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

    dao.decayDueBatch(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 10);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
    assertThat(sql.getValue())
        .contains("CASE LEAST(DATEDIFF(CURDATE(), last_activity_at), 9)")
        .contains(
            "CASE LEAST(GREATEST(DATEDIFF(COALESCE(last_decayed_date, last_activity_at), last_activity_at), 0), 9)")
        .doesNotContain("CURDATE() - INTERVAL 1 DAY, last_activity_at");
  }

  @Test
  void decayDueBatch_shouldAssignScoreBeforeLastDecayedDate_becauseMySqlReadsAssignmentsLeftToRight() {
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

    dao.decayDueBatch(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 10);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
    String statement = sql.getValue();
    assertThat(statement.indexOf("SET current_score = current_score"))
        .isNotNegative()
        .isLessThan(statement.indexOf("last_decayed_date = CURDATE()"));
  }

  @Test
  void creditExpiredSuppressions_shouldRunOneWriteWithTheBatchSize_andReturnRowsUpdated() {
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(4);

    int updated = dao.creditExpiredSuppressions(500);

    assertThat(updated).isEqualTo(4);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(jdbc).update(sql.capture(), params.capture());

    assertThat(sql.getValue())
        .contains("UPDATE ysmdm_admin.tbl_user_activity_service_score ")
        .contains("current_score = current_score + 50")
        .contains("suppressed_until = NULL")
        .contains("WHERE suppressed_until = CURDATE()")
        .contains("LIMIT :batchSize");
    assertThat(params.getValue().getValue("batchSize")).isEqualTo(500);
  }

  @Test
  void constructor_shouldFailStartup_whenSchemaIsMissing() {
    assertThatThrownBy(() -> new UserActivityScoreDecayDao(activityDb, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scheduler.activity-db.schema");
    assertThatThrownBy(() -> new UserActivityScoreDecayDao(activityDb, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_shouldRejectASchemaThatCouldInjectSql() {
    assertThatThrownBy(() -> new UserActivityScoreDecayDao(activityDb, "x; DROP TABLE t"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("letters, digits and underscores");
  }
}
