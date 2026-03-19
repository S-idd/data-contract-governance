package com.ideas.contracts.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public record PolicyPackConfig(
    String defaultPack,
    Map<String, PolicyPackDefinition> packs
) {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public static PolicyPackConfig load(Path path) {
    if (path == null || Files.notExists(path)) {
      return defaults();
    }
    try {
      PolicyPackConfig parsed = OBJECT_MAPPER.readValue(path.toFile(), PolicyPackConfig.class);
      return parsed.withDefaults();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read policy pack config: " + path, e);
    }
  }

  public static PolicyPackConfig defaults() {
    Map<String, PolicyPackDefinition> defaultPacks = new HashMap<>();
    defaultPacks.put(PolicyPackDefaults.BASELINE_NAME, baselineDefinition());
    return new PolicyPackConfig(PolicyPackDefaults.BASELINE_NAME, defaultPacks).withDefaults();
  }

  public PolicyPack resolve(String requestedPack) {
    String effectiveName = resolveName(requestedPack);
    PolicyPackDefinition definition = packs().getOrDefault(effectiveName, baselineDefinition());
    return toPolicyPack(effectiveName, definition);
  }

  public String resolveName(String requestedPack) {
    String normalized = normalizePackName(requestedPack);
    if (normalized != null && packs().containsKey(normalized)) {
      return normalized;
    }
    String normalizedDefault = normalizePackName(defaultPack);
    if (normalizedDefault != null && packs().containsKey(normalizedDefault)) {
      return normalizedDefault;
    }
    return PolicyPackDefaults.BASELINE_NAME;
  }

  private PolicyPackConfig withDefaults() {
    Map<String, PolicyPackDefinition> normalized = normalizePackDefinitions(packs);
    normalized.putIfAbsent(PolicyPackDefaults.BASELINE_NAME, baselineDefinition());

    String normalizedDefault = normalizePackName(defaultPack);
    if (normalizedDefault == null || !normalized.containsKey(normalizedDefault)) {
      normalizedDefault = PolicyPackDefaults.BASELINE_NAME;
    }
    return new PolicyPackConfig(normalizedDefault, normalized);
  }

  private PolicyPack toPolicyPack(String name, PolicyPackDefinition definition) {
    Map<RuleId, RuleSeverity> rules = new HashMap<>(PolicyPackDefaults.baselineRules());
    if (definition != null && definition.rules() != null) {
      for (Map.Entry<String, RuleSeverity> entry : definition.rules().entrySet()) {
        String key = normalizeRuleKey(entry.getKey());
        if (key == null) {
          continue;
        }
        RuleId ruleId = parseRuleId(key, entry.getKey());
        rules.put(ruleId, entry.getValue() == null ? RuleSeverity.BREAKING : entry.getValue());
      }
    }
    return new PolicyPack(name, Collections.unmodifiableMap(rules));
  }

  private Map<String, PolicyPackDefinition> normalizePackDefinitions(Map<String, PolicyPackDefinition> raw) {
    Map<String, PolicyPackDefinition> normalized = new HashMap<>();
    if (raw == null) {
      return normalized;
    }
    for (Map.Entry<String, PolicyPackDefinition> entry : raw.entrySet()) {
      String normalizedName = normalizePackName(entry.getKey());
      if (normalizedName == null) {
        continue;
      }
      normalized.put(normalizedName, entry.getValue());
    }
    return normalized;
  }

  private static PolicyPackDefinition baselineDefinition() {
    return new PolicyPackDefinition(
        "Baseline compatibility rules",
        baselineRulesAsStrings());
  }

  private static Map<String, RuleSeverity> baselineRulesAsStrings() {
    Map<String, RuleSeverity> rules = new HashMap<>();
    for (Map.Entry<RuleId, RuleSeverity> entry : PolicyPackDefaults.baselineRules().entrySet()) {
      rules.put(entry.getKey().name(), entry.getValue());
    }
    return rules;
  }

  private static String normalizePackName(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private static String normalizeRuleKey(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.toUpperCase(Locale.ROOT).replace('-', '_');
  }

  private static RuleId parseRuleId(String normalized, String raw) {
    try {
      return RuleId.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Unknown policy rule id: " + raw);
    }
  }

  public record PolicyPackDefinition(
      String description,
      Map<String, RuleSeverity> rules
  ) {
  }
}
