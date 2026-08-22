package com.ideas.contracts.core;

import java.math.BigDecimal;
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
    List<String> constraintTightened = new ArrayList<>();
    List<String> conditionalRestrictionAdded = new ArrayList<>();
    List<String> schemaRestrictionAdded = new ArrayList<>();

    Set<String> allFields = new HashSet<>();
    allFields.addAll(base.fields().keySet());
    allFields.addAll(candidate.fields().keySet());

    for (String field : allFields) {
      SchemaField before = base.fields().get(field);
      SchemaField after = candidate.fields().get(field);

      if (before == null && after != null) {
        fieldAdded.add(field);
        if (after.required() && !isWithinNewOneOfBranch(field, base)) {
          requiredAdded.add(field);
        }
        continue;
      }
      if (before != null && after == null) {
        if (isWithinRemovedOneOfBranch(field, candidate)) {
          continue;
        }
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
      constraintTightened.addAll(tightenedConstraints(field, before, after));
    }

    for (var conditional : candidate.conditionalRestrictions().entrySet()) {
      String before = base.conditionalRestrictions().get(conditional.getKey());
      if (!conditional.getValue().equals(before)) {
        conditionalRestrictionAdded.add(conditional.getKey());
      }
    }
    for (var restriction : candidate.schemaRestrictions().entrySet()) {
      String before = base.schemaRestrictions().get(restriction.getKey());
      if (!restriction.getValue().equals(before)) {
        schemaRestrictionAdded.add(restriction.getKey());
      }
    }

    fieldAdded.sort(String::compareTo);
    fieldRemoved.sort(String::compareTo);
    typeChanged.sort(String::compareTo);
    requiredAdded.sort(String::compareTo);
    requiredRemoved.sort(String::compareTo);
    enumAdded.sort(String::compareTo);
    enumRemoved.sort(String::compareTo);
    constraintTightened.sort(String::compareTo);
    conditionalRestrictionAdded.sort(String::compareTo);
    schemaRestrictionAdded.sort(String::compareTo);

    return new SchemaDiff(
        fieldAdded,
        fieldRemoved,
        typeChanged,
        requiredAdded,
        requiredRemoved,
        enumAdded,
        enumRemoved,
        constraintTightened,
        conditionalRestrictionAdded,
        schemaRestrictionAdded);
  }

  private List<String> tightenedConstraints(String field, SchemaField before, SchemaField after) {
    List<String> changes = new ArrayList<>();
    for (String keyword : after.constraints().keySet()) {
      String previous = before.constraints().get(keyword);
      String candidate = after.constraints().get(keyword);
      if (isTightened(keyword, previous, candidate)) {
        changes.add(field + "." + keyword + " (" + display(previous) + " -> " + candidate + ")");
      }
    }
    return changes;
  }

  private boolean isWithinNewOneOfBranch(String field, SchemaSnapshot base) {
    String branchPath = oneOfBranchPath(field);
    return branchPath != null && !base.fields().containsKey(branchPath);
  }

  private boolean isWithinRemovedOneOfBranch(String field, SchemaSnapshot candidate) {
    String branchPath = oneOfBranchPath(field);
    return branchPath != null && !field.equals(branchPath) && !candidate.fields().containsKey(branchPath);
  }

  private String oneOfBranchPath(String field) {
    int start = field.indexOf(".oneOf[");
    if (start < 0) {
      return null;
    }
    int end = field.indexOf(']', start);
    return end < 0 ? null : field.substring(0, end + 1);
  }

  private boolean isTightened(String keyword, String previous, String candidate) {
    if (candidate.equals(previous)) {
      return false;
    }
    return switch (keyword) {
      case "minimum", "exclusiveMinimum", "minLength", "minItems", "minContains", "minProperties" ->
          previous == null || number(candidate).compareTo(number(previous)) > 0;
      case "maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxContains", "maxProperties" ->
          previous == null || number(candidate).compareTo(number(previous)) < 0;
      case "additionalProperties" -> "false".equals(candidate) && !"false".equals(previous);
      case "uniqueItems" -> "true".equals(candidate) && !"true".equals(previous);
      case "multipleOf", "pattern", "format", "const" -> previous == null || !candidate.equals(previous);
      default -> false;
    };
  }

  private BigDecimal number(String value) {
    return new BigDecimal(value);
  }

  private String display(String value) {
    return value == null ? "unbounded" : value;
  }
}
