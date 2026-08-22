package com.ideas.contracts.core;

import java.util.Map;

public record SchemaSnapshot(
    Map<String, SchemaField> fields,
    Map<String, String> conditionalRestrictions,
    Map<String, String> schemaRestrictions
) {
  public SchemaSnapshot(Map<String, SchemaField> fields) {
    this(fields, Map.of(), Map.of());
  }

  public SchemaSnapshot(Map<String, SchemaField> fields, Map<String, String> conditionalRestrictions) {
    this(fields, conditionalRestrictions, Map.of());
  }

  public SchemaSnapshot {
    fields = fields == null ? Map.of() : Map.copyOf(fields);
    conditionalRestrictions = conditionalRestrictions == null ? Map.of() : Map.copyOf(conditionalRestrictions);
    schemaRestrictions = schemaRestrictions == null ? Map.of() : Map.copyOf(schemaRestrictions);
  }
}
