package com.ideas.contracts.service;

import java.time.Duration;

class CheckRunStorePostgresContractTest extends AbstractMetadataStoreContractTest {
  private static final String BASE_JDBC_URL = PostgresTestSupport.localJdbcUrl();
  private static final String USERNAME = PostgresTestSupport.localUsername();
  private static final String PASSWORD = PostgresTestSupport.localPassword();

  @Override
  protected MetadataStoreFixture createFixture() throws Exception {
    PostgresTestSupport.assumeLocalPostgresAvailable();
    String schema = PostgresTestSupport.randomSchema("unit_contract");
    String jdbcUrl = PostgresTestSupport.withCurrentSchema(BASE_JDBC_URL, schema);
    PostgresTestSupport.createSchema(BASE_JDBC_URL, USERNAME, PASSWORD, schema);

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
        PostgresTestSupport.dropSchemaQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, schema);
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
