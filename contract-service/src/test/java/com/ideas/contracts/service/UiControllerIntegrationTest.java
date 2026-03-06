package com.ideas.contracts.service;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "app.ui.enabled=true",
    "app.security.enabled=false"
})
class UiControllerIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @Autowired
  private MockMvc mockMvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensureTestPaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> checksDbPath.toString());
  }

  @BeforeAll
  void setUpData() throws Exception {
    Path contractDir = contractsRoot.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(
        contractDir.resolve("v1.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}");
    Files.writeString(
        contractDir.resolve("v2.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"}}}");

    String jdbcUrl = "jdbc:sqlite:" + checksDbPath;
    Flyway.configure()
        .dataSource(jdbcUrl, null, null)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("0"))
        .load()
        .migrate();

    try (Connection connection = DriverManager.getConnection(jdbcUrl);
         PreparedStatement insert = connection.prepareStatement("""
             INSERT OR REPLACE INTO check_runs (
               run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at
             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
             """)) {
      insert.setString(1, "ui-run-1");
      insert.setString(2, "orders.created");
      insert.setString(3, "v1");
      insert.setString(4, "v2");
      insert.setString(5, "FAIL");
      insert.setString(6, "[\"Field type changed: orderId (string -> integer)\"]");
      insert.setString(7, "[\"Enum value added: status.SHIPPED\"]");
      insert.setString(8, "ui-test");
      insert.setString(9, "2026-03-01T12:00:00Z");
      insert.executeUpdate();
    }
  }

  @Test
  void dashboardPageRendersRecentCheck() throws Exception {
    mockMvc.perform(get("/ui"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Dashboard")))
        .andExpect(content().string(containsString("ui-run-1")));
  }

  @Test
  void contractsPageSupportsSearch() throws Exception {
    mockMvc.perform(get("/ui/contracts").queryParam("q", "orders"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("orders.created")));
  }

  @Test
  void contractDetailPageShowsRecentChecks() throws Exception {
    mockMvc.perform(get("/ui/contracts/orders.created"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("orders.created")))
        .andExpect(content().string(containsString("ui-run-1")));
  }

  @Test
  void checkDetailPageShowsDeveloperGuidance() throws Exception {
    mockMvc.perform(get("/ui/checks/ui-run-1"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Developer Guidance")))
        .andExpect(content().string(containsString("compatible transitional field type")));
  }

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("ui-controller-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks-ui.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
