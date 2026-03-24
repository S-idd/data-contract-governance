package com.ideas.contracts.core;

import java.util.Set;

public record SchemaField(
    String type,
    boolean required,
    boolean nullable,
    Set<String> enumValues
) {
  public SchemaField {
    type = type == null || type.isBlank() ? "unknown" : type.trim().toLowerCase();
    enumValues = enumValues == null ? Set.of() : Set.copyOf(enumValues);
  }

  public String displayType() {
    return nullable && !type.contains("null") ? type + "|null" : type;
  }
}
