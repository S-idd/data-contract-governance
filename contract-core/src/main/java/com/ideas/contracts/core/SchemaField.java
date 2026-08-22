package com.ideas.contracts.core;

import java.util.Map;
import java.util.Set;

public record SchemaField(
    String type,
    boolean required,
    boolean nullable,
    Set<String> enumValues,
    Map<String, String> constraints
) {
  public SchemaField(String type, boolean required, boolean nullable, Set<String> enumValues) {
    this(type, required, nullable, enumValues, Map.of());
  }

  public SchemaField {
    type = type == null || type.isBlank() ? "unknown" : type.trim().toLowerCase();
    enumValues = enumValues == null ? Set.of() : Set.copyOf(enumValues);
    constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
  }

  public String displayType() {
    return nullable && !type.contains("null") ? type + "|null" : type;
  }
}
