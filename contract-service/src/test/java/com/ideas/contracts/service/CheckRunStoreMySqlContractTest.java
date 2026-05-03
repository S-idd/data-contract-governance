package com.ideas.contracts.service;

import java.time.Duration;
import org.junit.jupiter.api.Assumptions;

class CheckRunStoreMySqlContractTest extends AbstractMetadataStoreContractTest {
  private static final String BASE_JDBC_URL = MySqlTestSupport.localJdbcUrl();
  private static final String USERNAME = MySqlTestSupport.localUsername();
  private static final String PASSWORD = MySqlTestSupport.localPassword();

  @Override
  protected MetadataStoreFixture createFixture() throws Exception {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_contract");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    try {
      MySqlTestSupport.createDatabase(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    } catch (Exception provisionError) {
      Assumptions.assumeTrue(
          false,
          "Skipping MySQL contract test: unable to create test database. " + provisionError.getMessage());
    }

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl));
    store.initialize();

    return new MetadataStoreFixture() {
      @Override
      public MetadataStore store() {
        return store;
      }

      @Override
      public void close() {
        store.shutdown();
        MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, database);
      }
    };
  }

  private CheckStoreProperties baseProperties(String jdbcUrl) {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setUrl(jdbcUrl);
    properties.setUsername(USERNAME);
    properties.setPassword(PASSWORD);
    properties.setQueryTimeout(Duration.ofSeconds(1));
    properties.getPool().setConnectionTimeout(Duration.ofMillis(250));
    properties.getPool().setInitializationFailTimeout(Duration.ofMillis(-1));
    return properties;
  }
}
