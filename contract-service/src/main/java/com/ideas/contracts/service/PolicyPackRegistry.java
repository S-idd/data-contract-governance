package com.ideas.contracts.service;

import com.ideas.contracts.core.PolicyPack;
import com.ideas.contracts.core.PolicyPackConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PolicyPackRegistry {
  private final PolicyPackConfig config;

  public PolicyPackRegistry(
      @Value("${contracts.policy-packs:}") String configPath,
      @Value("${contracts.root:contracts}") String contractsRoot) {
    Path path = resolveConfigPath(configPath, contractsRoot);
    this.config = PolicyPackConfig.load(path);
  }

  public PolicyPack resolve(String requestedPack) {
    return config.resolve(requestedPack);
  }

  public String resolveName(String requestedPack) {
    return config.resolveName(requestedPack);
  }

  public String defaultPackName() {
    return config.resolveName(null);
  }

  private Path resolveConfigPath(String configPath, String contractsRoot) {
    if (configPath != null && !configPath.isBlank()) {
      return Paths.get(configPath.trim());
    }
    return Paths.get(contractsRoot).resolve("policy-packs.json");
  }
}
