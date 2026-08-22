package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies pool saturation behavior and that normal request churn returns every connection. */
@ExtendWith(MySqlDatabaseCleanupExtension.class)
class CheckRunStoreMySqlPoolBehaviorTest {
  private static final String BASE_JDBC_URL = MySqlTestSupport.localJdbcUrl();
  private static final String USERNAME = MySqlTestSupport.localUsername();
  private static final String PASSWORD = MySqlTestSupport.localPassword();

  @Test
  void recordsAcquisitionLatencyTimesOutWhenSaturatedAndDoesNotLeakConnections() throws Exception {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_pool_behavior");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    MySqlTestSupport.createDatabase(BASE_JDBC_URL, USERNAME, PASSWORD, database);

    CheckRunStore store = newStore(jdbcUrl);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    store.registerPoolMetrics(meters);
    try {
      store.initialize();
      HikariDataSource dataSource = dataSource(store);

      try (Connection heldConnection = dataSource.getConnection();
           PreparedStatement lockStatement = heldConnection.prepareStatement("SELECT 1 FOR UPDATE")) {
        heldConnection.setAutoCommit(false);
        lockStatement.execute();
        CheckRunStore.PoolSnapshot saturated = store.poolSnapshot();
        assertEquals(1, saturated.activeConnections());
        assertEquals(1, saturated.maximumPoolSize());

        CheckRunStoreException failure = assertThrows(
            CheckRunStoreException.class, () -> store.list("orders.created", null));
        assertTrue(failure.getCause().getMessage().contains("Connection is not available"));
        heldConnection.rollback();
      }

      for (int request = 0; request < 25; request++) {
        store.list("orders.created", null);
      }

      CheckRunStore.PoolSnapshot drained = store.poolSnapshot();
      assertEquals(0, drained.activeConnections(), "all request connections must be returned to Hikari");
      assertEquals(0, drained.threadsAwaitingConnection(), "no connection waiters may remain");
      assertTrue(drained.totalConnections() <= drained.maximumPoolSize());
      Timer acquisitionTimer = meters.find("hikaricp.connections.acquire").timer();
      assertNotNull(acquisitionTimer, "Hikari acquisition latency timer must be registered");
      assertTrue(acquisitionTimer.count() >= 27, "acquisition latency must cover initialization and request churn");
    } finally {
      store.shutdown();
      meters.close();
      MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    }
  }

  private CheckRunStore newStore(String jdbcUrl) {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setUrl(jdbcUrl);
    properties.setUsername(USERNAME);
    properties.setPassword(PASSWORD);
    properties.setQueryTimeout(Duration.ofSeconds(2));
    properties.getPool().setMaximumSize(1);
    properties.getPool().setMinimumIdle(0);
    properties.getPool().setConnectionTimeout(Duration.ofMillis(300));
    properties.getPool().setInitializationFailTimeout(Duration.ofMillis(-1));
    return new CheckRunStore(properties);
  }

  private HikariDataSource dataSource(CheckRunStore store) throws ReflectiveOperationException {
    Field field = CheckRunStore.class.getDeclaredField("dataSource");
    field.setAccessible(true);
    return (HikariDataSource) field.get(store);
  }
}
