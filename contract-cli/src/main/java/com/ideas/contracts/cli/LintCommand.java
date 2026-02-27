package com.ideas.contracts.cli;

import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.DefaultContractEngine;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(name = "lint", description = "Lint contract directory")
public class LintCommand implements Callable<Integer> {
  @CommandLine.Option(
      names = {"-p", "--path"},
      required = true,
      description = "Path to contract directory")
  private Path contractPath;

  @Override
  public Integer call() {
    ContractEngine engine = new DefaultContractEngine();
    try {
      engine.lint(contractPath);
      System.out.println("Lint passed: " + contractPath);
      return 0;
    } catch (IllegalStateException ex) {
      System.err.println("Lint failed: " + ex.getMessage());
      return 1;
    }
  }
}
