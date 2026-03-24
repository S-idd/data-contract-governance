package com.ideas.contracts.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "init-ci",
    description = "Generate a GitHub Actions contract validation workflow",
    mixinStandardHelpOptions = true)
public class InitCiCommand implements Callable<Integer> {
  @CommandLine.Option(
      names = "--output",
      defaultValue = ".github/workflows/contract-validation.yml",
      description = "Output workflow file path")
  private Path outputPath;

  @CommandLine.Option(names = "--force", description = "Overwrite existing workflow file")
  private boolean force;

  @Override
  public Integer call() {
    try {
      Path absolutePath = outputPath.toAbsolutePath();
      if (Files.exists(absolutePath) && !force) {
        System.err.println("Workflow already exists: " + absolutePath + " (use --force to overwrite)");
        return 1;
      }
      Path parent = absolutePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(absolutePath, workflowContent(), StandardCharsets.UTF_8);
      System.out.println("Generated workflow: " + absolutePath);
      return 0;
    } catch (Exception ex) {
      System.err.println("init-ci failed: " + ex.getMessage());
      return 2;
    }
  }

  private String workflowContent() {
    return """
        name: Contract Validation

        on:
          pull_request:
          push:
            branches: [ main ]

        jobs:
          validate-contracts:
            runs-on: ubuntu-latest
            steps:
              - name: Checkout
                uses: actions/checkout@v4
                with:
                  fetch-depth: 0

              - name: Setup Java
                uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '21'
                  cache: maven

              - name: Build CLI
                run: ./mvnw -pl contract-cli -am package -DskipTests

              - name: Validate Changed Contracts
                env:
                  BASE_SHA: ${{ github.event.pull_request.base.sha || github.event.before }}
                  HEAD_SHA: ${{ github.sha }}
                run: |
                  chmod +x scripts/ci/check-changed-contracts.sh
                  bash scripts/ci/check-changed-contracts.sh
        """;
  }
}
