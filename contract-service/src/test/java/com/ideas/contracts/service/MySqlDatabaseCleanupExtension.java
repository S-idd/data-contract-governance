package com.ideas.contracts.service;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

final class MySqlDatabaseCleanupExtension implements AfterAllCallback {
  @Override
  public void afterAll(ExtensionContext context) {
    MySqlTestSupport.cleanupDatabasesNow();
  }
}
