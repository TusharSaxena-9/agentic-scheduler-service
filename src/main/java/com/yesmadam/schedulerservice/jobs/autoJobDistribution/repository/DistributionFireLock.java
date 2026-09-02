package com.yesmadam.schedulerservice.jobs.autoJobDistribution.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Cross-process mutex around the select-and-fire critical section, replacing cron's in-JVM {@code
 * AtomicBoolean firing}.
 *
 * <p><b>Why this exists.</b> In cron, {@code selectAndFireNext()} was guarded by a plain {@code
 * AtomicBoolean} whose own comment justified it as "sufficient because cron runs as a single
 * instance". A single-JVM flag cannot hold that line once the pipeline is split, and it does not
 * hold it for two worker pods of this service either. The section is a read-then-act — "is any
 * distribution active right now?", then fire only if not — and without a shared mutex it degrades
 * into a time-of-check/time-of-use race where two processes both read "nothing active" and both
 * fire, breaking the one-active-at-a-time invariant the whole escalation model rests on.
 *
 * <p><b>Mechanism.</b> MySQL's {@code GET_LOCK}/{@code RELEASE_LOCK} — a named, server-side
 * advisory lock, so it coordinates every process pointed at the same database with no schema
 * change. Chosen over a lock table with {@code SELECT ... FOR UPDATE} because it needs no DDL and,
 * critically, the server releases it automatically if a holder's connection dies; a lock row would
 * strand and freeze distribution until someone cleared it by hand.
 *
 * <p>Acquired with a zero timeout, i.e. try-lock: a caller that cannot get it returns immediately
 * without firing, exactly as {@code compareAndSet(false, true)} did. Losing a tick is harmless —
 * the next one re-checks.
 *
 * <h2>Why this holds a {@link Connection} itself instead of using a {@code JdbcTemplate}</h2>
 *
 * <p>{@code GET_LOCK} is <b>session-scoped</b>, and a session is a physical connection. A {@code
 * JdbcTemplate} borrows a connection per statement and returns it to the pool immediately, so
 * {@code GET_LOCK}, the critical section and {@code RELEASE_LOCK} would each run on whatever
 * connection the pool happened to hand out. That breaks the lock in a way that is silent and
 * cumulative:
 *
 * <ul>
 *   <li>{@code RELEASE_LOCK} from a different session returns 0 and <b>does not release
 *       anything</b> — MySQL only lets the owning session release a named lock.
 *   <li>The lock therefore stays held by a pooled connection that is now idle. HikariCP keeps it
 *       alive, so the lock is not freed by connection death either.
 *   <li>Every later try-lock then fails, {@code runIfAcquired} returns null forever, and
 *       <b>distribution stops firing permanently</b> — with no error anywhere, because failing to
 *       take the lock is a normal, logged-at-warn outcome.
 * </ul>
 *
 * <p>So the connection is acquired once, used for acquire, section and release, and closed at the
 * end. It is taken from the <b>writer</b> pool explicitly rather than the {@code @Primary} routing
 * datasource: that one routes by the current transaction's read-only flag, which would put the lock
 * on a read replica for any caller that happens to be inside a read-only transaction. A lock taken
 * on a replica coordinates nothing.
 *
 * <p>The connection is also deliberately outside the caller's transaction — the lock must outlive
 * statement boundaries and be released even if the surrounding work rolls back.
 *
 * <p><b>Deployment note.</b> This depends on connection-level session affinity, so it is not
 * compatible with a multiplexing proxy in front of MySQL (ProxySQL's connection multiplexing, or
 * RDS Proxy pinning rules). Both handle {@code GET_LOCK} by pinning the session, but that must be
 * verified rather than assumed if a proxy is introduced.
 */
@Slf4j
@Component
public class DistributionFireLock {

  /**
   * Lock name. Must match byte-for-byte in every process that takes it — MySQL lock names are a
   * flat global namespace per server, compared as plain strings.
   */
  public static final String LOCK_NAME = "ym_auto_job_distribution_fire";

  /**
   * Zero = try-lock. Do not raise this: a queueing caller would hold a connection and serialise
   * ticks.
   */
  private static final int ACQUIRE_TIMEOUT_SECONDS = 0;

  private static final String ACQUIRE_SQL = "SELECT GET_LOCK(?, ?)";
  private static final String RELEASE_SQL = "SELECT RELEASE_LOCK(?)";

  private final DataSource writerDataSource;

  @Autowired
  public DistributionFireLock(@Qualifier("writerDataSource") DataSource writerDataSource) {
    this.writerDataSource = writerDataSource;
  }

  /**
   * Runs {@code criticalSection} while holding the lock, or skips it entirely if another process
   * holds it.
   *
   * @param criticalSection the select-and-fire work
   * @return the section's result, or {@code null} if the lock was not acquired
   */
  public <T> T runIfAcquired(Supplier<T> criticalSection) {
    Connection connection;
    try {
      connection = writerDataSource.getConnection();
    } catch (SQLException | RuntimeException ex) {
      // Fail closed: no connection means no lock, and firing unguarded risks two concurrent
      // distributions. One lost tick is the cheaper failure.
      log.error(
          "event=distribution_fire_lock_connection_failed lock={} error={}",
          LOCK_NAME,
          ex.getMessage(),
          ex);
      return null;
    }

    try {
      if (!tryAcquire(connection)) {
        log.warn("event=distribution_fire_lock_busy lock={} action=skipped_this_tick", LOCK_NAME);
        return null;
      }
      try {
        return criticalSection.get();
      } finally {
        release(connection);
      }
    } finally {
      close(connection);
    }
  }

  private boolean tryAcquire(Connection connection) {
    try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_SQL)) {
      statement.setString(1, LOCK_NAME);
      statement.setInt(2, ACQUIRE_TIMEOUT_SECONDS);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return false;
        }
        // 1 acquired, 0 timed out, NULL on error — and getInt() reports 0 for NULL, so wasNull()
        // has to be consulted rather than trusting the primitive.
        int acquired = resultSet.getInt(1);
        return !resultSet.wasNull() && acquired == 1;
      }
    } catch (SQLException | RuntimeException ex) {
      // Never let a lock-infrastructure failure fire the section unguarded.
      log.error(
          "event=distribution_fire_lock_acquire_failed lock={} error={}",
          LOCK_NAME,
          ex.getMessage(),
          ex);
      return false;
    }
  }

  private void release(Connection connection) {
    try (PreparedStatement statement = connection.prepareStatement(RELEASE_SQL)) {
      statement.setString(1, LOCK_NAME);
      try (ResultSet resultSet = statement.executeQuery()) {
        // 1 released, 0 held by another session, NULL if it did not exist. A 0 here would mean the
        // acquire and release ran on different sessions, which is the exact bug this class is
        // shaped
        // to avoid — so it is worth a loud log rather than being ignored.
        if (resultSet.next() && resultSet.getInt(1) != 1) {
          log.error(
              "event=distribution_fire_lock_not_released lock={} reason=not_owned_by_this_session",
              LOCK_NAME);
        }
      }
    } catch (SQLException | RuntimeException ex) {
      // Not fatal: closing the connection below drops the lock server-side.
      log.warn(
          "event=distribution_fire_lock_release_failed lock={} error={}",
          LOCK_NAME,
          ex.getMessage());
    }
  }

  private void close(Connection connection) {
    try {
      connection.close();
    } catch (SQLException | RuntimeException ex) {
      log.warn(
          "event=distribution_fire_lock_connection_close_failed lock={} error={}",
          LOCK_NAME,
          ex.getMessage());
    }
  }
}
