package com.ideas.contracts.cli;

import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.DefaultContractEngine;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(name = "diff", description = "Show semantic schema diff")
public class DiffCommand implements Callable<Integer> {
  @CommandLine.Option(names = "--base", required = true, description = "Base schema file path")
  private Path baseSchema;

  @CommandLine.Option(names = "--candidate", required = true, description = "Candidate schema file path")
  private Path candidateSchema;

  @Override
  public Integer call() {
    ContractEngine engine = new DefaultContractEngine();
    try {
      String diff = engine.diff(baseSchema, candidateSchema);
      System.out.println(diff);
      return 0;
    } catch (IllegalStateException ex) {
      System.err.println("Diff failed: " + ex.getMessage());
      return 1;
    }
  }
}
