package com.ideas.contracts.core;

import java.util.Map;

public record SchemaSnapshot(Map<String, SchemaField> fields) {
  public SchemaSnapshot {
    fields = fields == null ? Map.of() : Map.copyOf(fields);
  }
}
