package com.ideas.contracts.core;

public interface DiffEngine {
  SchemaDiff diff(SchemaSnapshot base, SchemaSnapshot candidate);
}
