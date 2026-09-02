package com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Pins the lock's safety properties. These are what make one firing mechanism actually one, so they
 * are asserted rather than assumed — and none of them need a database, because what matters is the
 * decision the lock makes from what MySQL returns.
 *
 * <p>The session-affinity tests are the important ones. {@code GET_LOCK} is session-scoped, so
 * acquire, critical section and release must all run on a single physical connection; a {@code
 * JdbcTemplate} would borrow one per statement and silently wedge distribution forever when {@code
 * RELEASE_LOCK} landed on a different session. That failure is invisible at runtime — the lock
 * simply stops being grantable — so it is pinned here instead.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DistributionFireLockTest {

  private static final String ACQUIRE = "SELECT GET_LOCK(?, ?)";
  private static final String RELEASE = "SELECT RELEASE_LOCK(?)";

  @Mock DataSource dataSource;
  @Mock Connection connection;
  @Mock PreparedStatement acquireStatement;
  @Mock PreparedStatement releaseStatement;
  @Mock ResultSet acquireResult;
  @Mock ResultSet releaseResult;

  private DistributionFireLock lock;

  @BeforeEach
  void setUp() throws SQLException {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(ACQUIRE)).thenReturn(acquireStatement);
    when(connection.prepareStatement(RELEASE)).thenReturn(releaseStatement);
    when(acquireStatement.executeQuery()).thenReturn(acquireResult);
    when(releaseStatement.executeQuery()).thenReturn(releaseResult);

    // Default: release succeeds and reports this session owned the lock.
    when(releaseResult.next()).thenReturn(true);
    when(releaseResult.getInt(1)).thenReturn(1);

    lock = new DistributionFireLock(dataSource);
  }

  /** Makes GET_LOCK return {@code value}, or SQL NULL when {@code value} is null. */
  private void acquireReturns(Integer value) throws SQLException {
    when(acquireResult.next()).thenReturn(true);
    when(acquireResult.getInt(1)).thenReturn(value == null ? 0 : value);
    when(acquireResult.wasNull()).thenReturn(value == null);
  }

  @Test
  @DisplayName("runs the critical section and releases when the lock is granted")
  void runsAndReleasesWhenGranted() throws SQLException {
    acquireReturns(1);
    AtomicBoolean ran = new AtomicBoolean(false);

    String result =
        lock.runIfAcquired(
            () -> {
              ran.set(true);
              return "fired";
            });

    assertThat(ran).isTrue();
    assertThat(result).isEqualTo("fired");
    verify(releaseStatement).executeQuery();
  }

  @Test
  @DisplayName("acquire, section and release all run on one physical connection")
  void usesASingleConnectionThroughout() throws SQLException {
    acquireReturns(1);
    AtomicBoolean ranInsideConnection = new AtomicBoolean(false);

    lock.runIfAcquired(
        () -> {
          ranInsideConnection.set(true);
          return "fired";
        });

    // Exactly one connection is borrowed, and it is closed only after the release. Borrowing twice
    // would mean RELEASE_LOCK could land on a session that never held the lock, which MySQL
    // ignores.
    verify(dataSource).getConnection();
    InOrder order = inOrder(connection, acquireStatement, releaseStatement);
    order.verify(connection).prepareStatement(ACQUIRE);
    order.verify(acquireStatement).executeQuery();
    order.verify(connection).prepareStatement(RELEASE);
    order.verify(releaseStatement).executeQuery();
    order.verify(connection).close();
    assertThat(ranInsideConnection).isTrue();
  }

  @Test
  @DisplayName("a release that reports a foreign session is logged, not silently accepted")
  void releaseFromWrongSessionIsDetected() throws SQLException {
    acquireReturns(1);
    when(releaseResult.getInt(1)).thenReturn(0); // MySQL: not held by this session

    // The call still completes — there is nothing useful to do at this point — but the 0 is the
    // signature of the session-affinity bug, so it must not pass unnoticed.
    assertThat(lock.runIfAcquired(() -> "fired")).isEqualTo("fired");
    verify(connection).close();
  }

  @Test
  @DisplayName("skips the critical section entirely when another process holds the lock")
  void skipsWhenLockHeldElsewhere() throws SQLException {
    acquireReturns(0); // GET_LOCK timed out — someone else is firing
    AtomicBoolean ran = new AtomicBoolean(false);

    Object result =
        lock.runIfAcquired(
            () -> {
              ran.set(true);
              return "fired";
            });

    assertThat(ran).isFalse();
    assertThat(result).isNull();
    verify(releaseStatement, never()).executeQuery();
    verify(connection).close();
  }

  @Test
  @DisplayName("fails closed: a broken lock query skips the section rather than firing unguarded")
  void failsClosedOnAcquireError() throws SQLException {
    when(acquireStatement.executeQuery()).thenThrow(new SQLException("connection gone"));
    AtomicBoolean ran = new AtomicBoolean(false);

    Object result =
        lock.runIfAcquired(
            () -> {
              ran.set(true);
              return "fired";
            });

    // Failing open would risk two concurrent distributions; failing closed costs one tick.
    assertThat(ran).isFalse();
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("fails closed when no connection can be obtained at all")
  void failsClosedWhenConnectionUnavailable() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));
    AtomicBoolean ran = new AtomicBoolean(false);

    assertThat(
            lock.runIfAcquired(
                () -> {
                  ran.set(true);
                  return "fired";
                }))
        .isNull();
    assertThat(ran).isFalse();
  }

  @Test
  @DisplayName("a NULL from GET_LOCK is treated as not-acquired")
  void nullAcquireIsNotAcquired() throws SQLException {
    acquireReturns(null);

    assertThat(lock.runIfAcquired(() -> "fired")).isNull();
  }

  @Test
  @DisplayName("an empty GET_LOCK result set is treated as not-acquired")
  void emptyAcquireResultIsNotAcquired() throws SQLException {
    when(acquireResult.next()).thenReturn(false);

    assertThat(lock.runIfAcquired(() -> "fired")).isNull();
  }

  @Test
  @DisplayName("releases the lock and closes the connection even when the section throws")
  void releasesOnException() throws SQLException {
    acquireReturns(1);

    assertThatThrownBy(
            () ->
                lock.runIfAcquired(
                    () -> {
                      throw new IllegalStateException("fire failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    // Without this, one failed fire would wedge distribution for every process.
    verify(releaseStatement).executeQuery();
    verify(connection).close();
  }

  @Test
  @DisplayName("a failed release does not propagate — closing the connection frees the lock")
  void releaseFailureIsSwallowed() throws SQLException {
    acquireReturns(1);
    when(releaseStatement.executeQuery()).thenThrow(new SQLException("connection gone"));

    assertThat(lock.runIfAcquired(() -> "fired")).isEqualTo("fired");
    verify(connection).close();
  }
}
