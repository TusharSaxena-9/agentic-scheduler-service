package com.yesmadam.schedulerservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import com.zaxxer.hikari.HikariDataSource;

class ActivityDbConfigTest {

  private final ActivityDbConfig config = new ActivityDbConfig();

  private static HikariDataSource configured(String url, String user, String password) {
    HikariDataSource ds = mock(HikariDataSource.class);
    when(ds.getJdbcUrl()).thenReturn(url);
    when(ds.getUsername()).thenReturn(user);
    when(ds.getPassword()).thenReturn(password);
    return ds;
  }

  @Test
  void activityDb_shouldBeCreatedAndReleaseItsProbeConnection_whenConfiguredAndReachable()
      throws Exception {
    HikariDataSource ds = configured("jdbc:mysql://host/db", "user", "secret");
    Connection probe = mock(Connection.class);
    when(ds.getConnection()).thenReturn(probe);

    ActivityDb db = config.activityDb(ds, 60);

    assertThat(db).isNotNull();
    verify(probe).close();
  }

  @Test
  void activityDb_shouldFailStartup_whenUrlIsMissing() {
    assertThatThrownBy(() -> config.activityDb(configured(null, "user", "secret"), 60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jdbc-url");
  }

  @Test
  void activityDb_shouldFailStartup_whenUsernameIsBlank() {
    assertThatThrownBy(() -> config.activityDb(configured("jdbc:mysql://h/d", " ", "secret"), 60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username");
  }

  @Test
  void activityDb_shouldFailStartup_whenPasswordIsMissing() {
    assertThatThrownBy(() -> config.activityDb(configured("jdbc:mysql://h/d", "user", null), 60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("password");
  }

  @Test
  void activityDb_shouldFailStartup_whenTheDatabaseIsUnreachable() throws Exception {
    HikariDataSource ds = configured("jdbc:mysql://host/db", "user", "secret");
    when(ds.getConnection()).thenThrow(new SQLException("connection refused"));

    assertThatThrownBy(() -> config.activityDb(ds, 60))
        .isInstanceOf(SQLException.class)
        .hasMessage("connection refused");
  }

  @Test
  void config_shouldOnlyDeclareThePoolAndTheHolder_soSpringBootKeepsItsPrimaryJpaStack() {
    Set<Class<?>> beanTypes =
        Arrays.stream(ActivityDbConfig.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Bean.class))
            .map(Method::getReturnType)
            .collect(Collectors.toSet());

    assertThat(beanTypes).containsExactlyInAnyOrder(HikariDataSource.class, ActivityDb.class);
  }
}
