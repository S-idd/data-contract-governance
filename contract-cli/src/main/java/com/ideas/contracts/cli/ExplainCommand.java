package com.ideas.contracts.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "explain",
    description = "Explain a recorded check run result",
    mixinStandardHelpOptions = true)
public class ExplainCommand implements Callable<Integer> {
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  @CommandLine.Option(names = "--run", required = true, description = "Run ID to explain")
  private String runId;

  @CommandLine.Option(names = "--db", description = "SQLite path (default: checks.db)")
  private Path dbPath;

  @CommandLine.Option(names = "--jdbc-url", description = "Optional JDBC URL for check-store query")
  private String jdbcUrl;

  @CommandLine.Option(names = "--db-user", description = "Optional database username for --jdbc-url")
  private String dbUser;

  @CommandLine.Option(names = "--db-password", description = "Optional database password for --jdbc-url")
  private String dbPassword;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public Integer call() {
    String sql = """
        SELECT status, breaking_changes, warnings
        FROM check_runs
        WHERE run_id = ?
        LIMIT 1
        """;
    try (Connection connection = CliDbSupport.openConnection(dbPath, jdbcUrl, dbUser, dbPassword);
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, runId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          System.err.println("Run not found: " + runId);
          return 1;
        }
        List<String> breaking = parseList(rs.getString("breaking_changes"));
        List<String> warnings = parseList(rs.getString("warnings"));
        printExplanation(breaking, warnings, rs.getString("status"));
        return breaking.isEmpty() ? 0 : 1;
      }
    } catch (Exception ex) {
      System.err.println("Explain failed: " + ex.getMessage());
      return 2;
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

  private void printExplanation(List<String> breakingChanges, List<String> warnings, String status) {
    if (breakingChanges.isEmpty()) {
      System.out.println("✅ No Breaking Changes Detected");
      if (!warnings.isEmpty()) {
        System.out.println();
        System.out.println("Warnings:");
        warnings.forEach(item -> System.out.println("- " + item));
      }
      return;
    }

    String headline = humanMessage(breakingChanges.get(0));
    String why = whyItMatters(breakingChanges.get(0));
    String fix = howToFix(breakingChanges.get(0));

    System.out.println("❌ Breaking Change Detected");
    System.out.println();
    System.out.println(headline);
    System.out.println();
    System.out.println("Why this matters:");
    System.out.println("- " + why);
    System.out.println("- Existing integrations can fail at runtime");
    System.out.println();
    System.out.println("How to fix:");
    System.out.println("- " + fix);
    System.out.println("- Mark field optional OR bump major version");
    System.out.println();
    System.out.println("Example:");
    System.out.println("{");
    System.out.println("  \"userId\": { \"type\": \"string\", \"nullable\": true }");
    System.out.println("}");
    if (status != null && !status.isBlank()) {
      System.out.println();
      System.out.println("Recorded status: " + status);
    }
  }

  private String humanMessage(String message) {
    if (message == null || message.isBlank()) {
      return "A breaking change was detected.";
    }
    if (message.startsWith("Field removed: ")) {
      String field = message.substring("Field removed: ".length());
      return "Field '" + field + "' was removed.";
    }
    if (message.startsWith("Field type changed: ")) {
      String field = message.substring("Field type changed: ".length()).split(" ")[0];
      return "Field '" + field + "' changed type.";
    }
    if (message.startsWith("Required field added: ")) {
      String field = message.substring("Required field added: ".length());
      return "Field '" + field + "' is now required.";
    }
    if (message.startsWith("Enum value removed: ")) {
      return "A previously allowed enum value was removed.";
    }
    return message;
  }

  private String whyItMatters(String message) {
    if (message == null) {
      return "Existing consumers may fail to parse payloads.";
    }
    if (message.startsWith("Field removed: ")) {
      return "Existing consumers expect this field and removal can break deserialization.";
    }
    if (message.startsWith("Field type changed: ")) {
      return "Type changes can break parsers and downstream validation logic.";
    }
    if (message.startsWith("Required field added: ")) {
      return "Older producers may not send the new field, causing validation failures.";
    }
    if (message.startsWith("Enum value removed: ")) {
      return "Existing payload values may be rejected by consumers expecting prior enums.";
    }
    return "Existing consumers can break at runtime.";
  }

  private String howToFix(String message) {
    if (message == null) {
      return "Preserve backward compatibility or release a major version.";
    }
    if (message.startsWith("Field removed: ")) {
      return "Re-introduce the field as optional and deprecate before removal.";
    }
    if (message.startsWith("Field type changed: ")) {
      return "Maintain the previous type or add a new field with the new type.";
    }
    if (message.startsWith("Required field added: ")) {
      return "Make the new field optional for a backward-compatible rollout.";
    }
    if (message.startsWith("Enum value removed: ")) {
      return "Keep legacy enum values until all consumers migrate.";
    }
    return "Preserve compatibility semantics for existing consumers.";
  }
}
