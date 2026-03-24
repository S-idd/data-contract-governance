package com.ideas.contracts.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "impact-analysis",
    description = "Analyze consumer impact for a specific contract version",
    mixinStandardHelpOptions = true)
public class ImpactAnalysisCommand implements Callable<Integer> {
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  @CommandLine.Option(names = "--contract", required = true, description = "Contract name (for example orders.created)")
  private String contractId;

  @CommandLine.Option(names = "--version", required = true, description = "Contract version (for example v2)")
  private String version;

  @CommandLine.Option(names = "--contracts-root", defaultValue = "contracts", description = "Contracts root directory")
  private Path contractsRoot;

  @CommandLine.Option(names = "--db", description = "SQLite path (default: checks.db)")
  private Path dbPath;

  @CommandLine.Option(names = "--jdbc-url", description = "Optional JDBC URL for check-store query")
  private String jdbcUrl;

  @CommandLine.Option(names = "--db-user", description = "Optional database username for --jdbc-url")
  private String dbUser;

  @CommandLine.Option(names = "--db-password", description = "Optional database password for --jdbc-url")
  private String dbPassword;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final YAMLMapper yamlMapper = new YAMLMapper();

  @Override
  public Integer call() {
    try {
      List<String> consumers = loadConsumers();
      RunOutcome outcome = loadLatestOutcome();

      System.out.println("Affected Consumers:");
      if (consumers.isEmpty()) {
        System.out.println("- (none declared)");
      } else {
        consumers.forEach(consumer -> System.out.println("- " + consumer));
      }
      System.out.println();
      System.out.println("Risk Level: " + outcome.riskLevel());
      System.out.println();
      System.out.println("Reason:");
      System.out.println("- " + outcome.reason());
      return "HIGH".equals(outcome.riskLevel()) ? 1 : 0;
    } catch (Exception ex) {
      System.err.println("Impact analysis failed: " + ex.getMessage());
      return 2;
    }
  }

  private List<String> loadConsumers() throws Exception {
    Path metadataPath = contractsRoot.resolve(contractId).resolve("metadata.yaml");
    if (!Files.exists(metadataPath)) {
      return List.of();
    }
    Map<?, ?> yaml = yamlMapper.readValue(metadataPath.toFile(), Map.class);
    Object consumersNode = yaml.get("consumers");
    if (consumersNode == null) {
      return List.of();
    }
    if (consumersNode instanceof List<?> list) {
      List<String> consumers = new ArrayList<>();
      for (Object item : list) {
        if (item != null && !item.toString().isBlank()) {
          consumers.add(item.toString().trim());
        }
      }
      return consumers;
    }
    String raw = consumersNode.toString();
    if (raw.isBlank()) {
      return List.of();
    }
    List<String> consumers = new ArrayList<>();
    for (String part : raw.split(",")) {
      if (!part.isBlank()) {
        consumers.add(part.trim());
      }
    }
    return consumers;
  }

  private RunOutcome loadLatestOutcome() throws Exception {
    String sql = """
        SELECT breaking_changes, warnings
        FROM check_runs
        WHERE contract_id = ? AND candidate_version = ?
        ORDER BY created_at DESC
        LIMIT 1
        """;
    try (Connection connection = CliDbSupport.openConnection(dbPath, jdbcUrl, dbUser, dbPassword);
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, contractId);
      statement.setString(2, version);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          return new RunOutcome("LOW", "No historical check run found for this version.");
        }
        List<String> breaking = parseList(rs.getString("breaking_changes"));
        List<String> warnings = parseList(rs.getString("warnings"));
        if (!breaking.isEmpty()) {
          return new RunOutcome("HIGH", breaking.get(0));
        }
        if (!warnings.isEmpty()) {
          return new RunOutcome("MEDIUM", warnings.get(0));
        }
        return new RunOutcome("LOW", "No breaking or warning changes detected.");
      }
    }
  }

  private List<String> parseList(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      List<String> parsed = objectMapper.readValue(raw, STRING_LIST_TYPE);
      return parsed == null ? List.of() : parsed;
    } catch (Exception ex) {
      return List.of(raw);
    }
  }

  private record RunOutcome(String riskLevel, String reason) {}
}
