package com.ideas.contracts.core;

import java.util.List;

public record SchemaDiff(
    List<String> fieldAdded,
    List<String> fieldRemoved,
    List<String> typeChanged,
    List<String> requiredAdded,
    List<String> requiredRemoved,
    List<String> enumAdded,
    List<String> enumRemoved,
    List<String> constraintTightened,
    List<String> conditionalRestrictionAdded,
    List<String> schemaRestrictionAdded
) {
  public SchemaDiff(
      List<String> fieldAdded,
      List<String> fieldRemoved,
      List<String> typeChanged,
      List<String> requiredAdded,
      List<String> requiredRemoved,
      List<String> enumAdded,
      List<String> enumRemoved) {
    this(fieldAdded, fieldRemoved, typeChanged, requiredAdded, requiredRemoved, enumAdded, enumRemoved, List.of(), List.of(), List.of());
  }

  public SchemaDiff {
    fieldAdded = fieldAdded == null ? List.of() : List.copyOf(fieldAdded);
    fieldRemoved = fieldRemoved == null ? List.of() : List.copyOf(fieldRemoved);
    typeChanged = typeChanged == null ? List.of() : List.copyOf(typeChanged);
    requiredAdded = requiredAdded == null ? List.of() : List.copyOf(requiredAdded);
    requiredRemoved = requiredRemoved == null ? List.of() : List.copyOf(requiredRemoved);
    enumAdded = enumAdded == null ? List.of() : List.copyOf(enumAdded);
    enumRemoved = enumRemoved == null ? List.of() : List.copyOf(enumRemoved);
    constraintTightened = constraintTightened == null ? List.of() : List.copyOf(constraintTightened);
    conditionalRestrictionAdded = conditionalRestrictionAdded == null ? List.of() : List.copyOf(conditionalRestrictionAdded);
    schemaRestrictionAdded = schemaRestrictionAdded == null ? List.of() : List.copyOf(schemaRestrictionAdded);
  }
}
