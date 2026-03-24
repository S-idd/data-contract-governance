package com.ideas.contracts.cli;

import picocli.CommandLine;

@CommandLine.Command(
    name = "contract",
    mixinStandardHelpOptions = true,
    description = "Data contract governance CLI",
    subcommands = {
        LintCommand.class,
        DiffCommand.class,
        CheckCompatCommand.class,
        ExplainCommand.class,
        ImpactAnalysisCommand.class,
        InitCiCommand.class
    }
)
public class ContractCliApplication implements Runnable {
  public static void main(String[] args) {
    int exitCode = new CommandLine(new ContractCliApplication()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
