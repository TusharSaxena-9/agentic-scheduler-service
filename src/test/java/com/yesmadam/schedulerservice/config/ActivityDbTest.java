package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActivityDbTest {

  @Mock DataSource dataSource;
  @Mock Connection connection;
  @Mock PreparedStatement statement;

  private ActivityDb db;

  @BeforeEach
  void setUp() throws Exception {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    lenient().when(connection.prepareStatement(anyString())).thenReturn(statement);
    lenient().when(statement.executeUpdate()).thenReturn(3);
    db = new ActivityDb(dataSource, 60);
  }

  @Test
  void write_shouldCommitAndReturnTheCallbackResult_whenTheCallbackSucceeds() throws Exception {
    int updated = db.write(jdbc -> jdbc.update("UPDATE t SET c = :v", Map.of("v", 1)));

    assertThat(updated).isEqualTo(3);
    verify(connection).commit();
    verify(connection, never()).rollback();
    verify(connection).close();
  }

  @Test
  void write_shouldRollBackAndRethrow_whenTheCallbackThrows() throws Exception {
    assertThatThrownBy(
            () ->
                db.write(
                    jdbc -> {
                      jdbc.update("UPDATE t SET c = :v", Map.of("v", 1));
                      throw new IllegalStateException("second statement failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("second statement failed");

    verify(connection).rollback();
    verify(connection, never()).commit();
    verify(connection).close();
  }

  @Test
  void write_shouldNotMarkTheConnectionReadOnly() throws Exception {
    db.write(jdbc -> jdbc.update("UPDATE t SET c = :v", Map.of("v", 1)));

    verify(connection, never()).setReadOnly(true);
  }

  @Test
  void read_shouldMarkTheConnectionReadOnlyAndReleaseIt() throws Exception {
    Integer result = db.read(jdbc -> 42);

    assertThat(result).isEqualTo(42);
    verify(connection).setReadOnly(true);
    verify(connection).close();
  }
}
