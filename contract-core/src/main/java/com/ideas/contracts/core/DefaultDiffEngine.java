package com.ideas.contracts.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DefaultDiffEngine implements DiffEngine {
  @Override
  public SchemaDiff diff(SchemaSnapshot base, SchemaSnapshot candidate) {
    if (base == null || candidate == null) {
      throw new CompatibilityException("Both base and candidate schemas are required.");
    }

    List<String> fieldAdded = new ArrayList<>();
    List<String> fieldRemoved = new ArrayList<>();
    List<String> typeChanged = new ArrayList<>();
    List<String> requiredAdded = new ArrayList<>();
    List<String> requiredRemoved = new ArrayList<>();
    List<String> enumAdded = new ArrayList<>();
    List<String> enumRemoved = new ArrayList<>();

    Set<String> allFields = new HashSet<>();
    allFields.addAll(base.fields().keySet());
    allFields.addAll(candidate.fields().keySet());

    for (String field : allFields) {
      SchemaField before = base.fields().get(field);
      SchemaField after = candidate.fields().get(field);

      if (before == null && after != null) {
        fieldAdded.add(field);
        if (after.required()) {
          requiredAdded.add(field);
        }
        continue;
      }
      if (before != null && after == null) {
        fieldRemoved.add(field);
        if (before.required()) {
          requiredRemoved.add(field);
        }
        continue;
      }
      if (before == null || after == null) {
        continue;
      }

      if (!before.type().equals(after.type()) || before.nullable() != after.nullable()) {
        typeChanged.add(field + " (" + before.displayType() + " -> " + after.displayType() + ")");
      }
      if (!before.required() && after.required()) {
        requiredAdded.add(field);
      }
      if (before.required() && !after.required()) {
        requiredRemoved.add(field);
      }

      for (String enumValue : after.enumValues()) {
        if (!before.enumValues().contains(enumValue)) {
          enumAdded.add(field + "." + enumValue);
        }
      }
      for (String enumValue : before.enumValues()) {
        if (!after.enumValues().contains(enumValue)) {
          enumRemoved.add(field + "." + enumValue);
        }
      }
    }

    fieldAdded.sort(String::compareTo);
    fieldRemoved.sort(String::compareTo);
    typeChanged.sort(String::compareTo);
    requiredAdded.sort(String::compareTo);
    requiredRemoved.sort(String::compareTo);
    enumAdded.sort(String::compareTo);
    enumRemoved.sort(String::compareTo);

    return new SchemaDiff(
        fieldAdded,
        fieldRemoved,
        typeChanged,
        requiredAdded,
        requiredRemoved,
        enumAdded,
        enumRemoved);
  }
}
