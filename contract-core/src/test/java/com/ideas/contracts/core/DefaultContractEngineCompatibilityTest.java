package com.ideas.contracts.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultContractEngineCompatibilityTest {
  private final DefaultContractEngine engine = new DefaultContractEngine();

  @TempDir
  Path tempDir;

  @Test
  void checkCompatibilityFailsOnBreakingChanges() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string"},
            "status": {"type": "string", "enum": ["NEW", "DONE"]}
          },
          "required": ["id"]
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "integer"},
            "status": {"type": "string", "enum": ["NEW"]},
            "region": {"type": "string"}
          },
          "required": ["id", "region"]
        }
        """
    );

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Field type changed: id")));
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Enum value removed: status.DONE")));
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Required field added: region")));
  }

  @Test
  void checkCompatibilityPassesAndWarnsOnEnumAddition() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW"]}
          }
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW", "DONE"]}
          }
        }
        """
    );

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.PASS, result.status());
    assertTrue(result.warnings().stream().anyMatch(m -> m.contains("Enum value added: status.DONE")));
  }

  @Test
  void checkCompatibilityRespectsPolicyPackOverrides() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW"]}
          }
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW", "DONE"]}
          }
        }
        """
    );

    EnumMap<RuleId, RuleSeverity> rules = new EnumMap<>(PolicyPackDefaults.baselineRules());
    rules.put(RuleId.ENUM_VALUE_ADDED, RuleSeverity.BREAKING);
    PolicyPack strictPack = new PolicyPack("strict", rules);

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD, strictPack);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Enum value added: status.DONE")));
  }

  @Test
  void diffShowsSemanticChanges() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string"},
            "status": {"type": "string", "enum": ["NEW"]}
          }
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string"},
            "status": {"type": "string", "enum": ["NEW", "DONE"]},
            "amount": {"type": "number"}
          },
          "required": ["amount"]
        }
        """
    );

    String diff = engine.diff(base, candidate);

    assertTrue(diff.contains("+ field added: amount"));
    assertTrue(diff.contains("! required added: amount"));
    assertTrue(diff.contains("~ enum value added: status.DONE"));
  }

  @Test
  void supportsEnterpriseSchemasWithLocalReferencesNestedObjectsAndArrays() throws IOException {
    Path schema = writeSchema(
        "erp-sales-order.json",
        """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$defs": {
            "address": {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "line1": {"type": "string", "minLength": 1, "maxLength": 200},
                "country": {"type": "string", "pattern": "^[A-Z]{2}$"}
              },
              "required": ["line1", "country"]
            }
          },
          "type": "object",
          "properties": {
            "orderId": {"type": "string", "pattern": "^SO-[0-9]+$"},
            "billingAddress": {"$ref": "#/$defs/address"},
            "lineItems": {
              "type": "array",
              "minItems": 1,
              "items": {
                "type": "object",
                "properties": {
                  "sku": {"type": "string"},
                  "quantity": {"type": "number", "exclusiveMinimum": 0}
                },
                "required": ["sku", "quantity"]
              }
            }
          },
          "required": ["orderId", "billingAddress", "lineItems"]
        }
        """
    );

    SchemaSnapshot snapshot = engine.schemaLoader().loadSchema(schema);

    assertEquals("object", snapshot.fields().get("billingAddress").type());
    assertTrue(snapshot.fields().get("billingAddress.line1").required());
    assertEquals("200", snapshot.fields().get("billingAddress.line1").constraints().get("maxLength"));
    assertEquals("object", snapshot.fields().get("lineItems[]").type());
    assertTrue(snapshot.fields().get("lineItems[].quantity").required());
    assertEquals("0", snapshot.fields().get("lineItems[].quantity").constraints().get("exclusiveMinimum"));
  }

  @Test
  void failsCompatibilityWhenNestedConstraintTightens() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "$defs": {"address": {"type": "object", "properties": {"postalCode": {"type": "string", "maxLength": 12}}}},
          "type": "object",
          "properties": {"shippingAddress": {"$ref": "#/$defs/address"}}
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "$defs": {"address": {"type": "object", "properties": {"postalCode": {"type": "string", "maxLength": 8}}}},
          "type": "object",
          "properties": {"shippingAddress": {"$ref": "#/$defs/address"}}
        }
        """
    );

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream()
        .anyMatch(message -> message.contains("Constraint tightened: shippingAddress.postalCode.maxLength (12 -> 8)")));
    assertFalse(result.breakingChanges().stream().anyMatch(message -> message.contains("Field removed")));
  }

  @Test
  void supportsOneOfAllOfAndConditionalRequirements() throws IOException {
    Path schema = writeSchema("purchase-order.json", composedPurchaseOrderSchema("BANK_TRANSFER", 1));

    SchemaSnapshot snapshot = engine.schemaLoader().loadSchema(schema);

    assertEquals("oneof", snapshot.fields().get("paymentTerms.method").type());
    assertEquals("object", snapshot.fields().get("paymentTerms.method.oneOf[type=CREDIT_CARD]").type());
    assertTrue(snapshot.fields().get("paymentTerms.method.oneOf[type=CREDIT_CARD].cardLast4").required());
    assertTrue(snapshot.fields().containsKey("paymentTerms.method.oneOf[type=BANK_TRANSFER].type"));
    assertTrue(snapshot.conditionalRestrictions().containsKey("root when status=APPROVED"));
  }

  @Test
  void passesWhenOneOfAddsAnOptionalPaymentMethodBranch() throws IOException {
    Path base = writeSchema("base.json", composedPurchaseOrderSchema(null, 1));
    Path candidate = writeSchema("candidate.json", composedPurchaseOrderSchema("WIRE", 1));

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.PASS, result.status(), () -> result.breakingChanges().toString());
  }

  @Test
  void failsWhenOneOfBranchIsRemovedOrConditionalRestrictionTightens() throws IOException {
    Path base = writeSchema("base.json", composedPurchaseOrderSchema("BANK_TRANSFER", 1));
    Path candidate = writeSchema("candidate.json", composedPurchaseOrderSchema(null, 2));

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream().anyMatch(
        message -> message.contains("Field removed: paymentTerms.method.oneOf[type=BANK_TRANSFER]")));
    assertTrue(result.breakingChanges().stream().anyMatch(
        message -> message.contains("Conditional restriction added or changed: root when status=APPROVED")));
  }

  @Test
  void supportsClassifiedJsonSchemaKeywordsWithoutTreatingAnnotationsAsConstraints() throws IOException {
    Path schema = writeSchema(
        "classified-keywords.json",
        """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "https://example.test/purchase-order",
          "$anchor": "purchase-order",
          "$vocabulary": {"https://json-schema.org/draft/2020-12/vocab/validation": true},
          "$defs": {"identifier": {"type": "string", "minLength": 1}},
          "type": "object",
          "maxProperties": 20,
          "properties": {
            "id": {"$ref": "#/$defs/identifier", "title": "Identifier", "examples": ["PO-1"]},
            "quantity": {"type": "number", "multipleOf": 1, "minimum": 0, "maximum": 100},
            "metadata": {
              "type": "object",
              "minProperties": 1,
              "patternProperties": {"^x-": {"type": "string"}},
              "additionalProperties": false,
              "propertyNames": {"pattern": "^[A-Za-z-]+$"},
              "dependentRequired": {"country": ["postalCode"]},
              "dependentSchemas": {"country": {"properties": {"postalCode": {"type": "string"}}}},
              "unevaluatedProperties": false
            },
            "tags": {
              "type": "array",
              "items": {"type": "string"},
              "prefixItems": [{"type": "string"}],
              "contains": {"type": "string", "const": "priority"},
              "minContains": 1,
              "maxContains": 2,
              "unevaluatedItems": false
            },
            "closedTags": {"type": "array", "items": false},
            "openExtension": true,
            "disabledExtension": false,
            "choice": {"anyOf": [{"type": "string"}, {"type": "integer"}]},
            "payment": {
              "type": "string",
              "anyOf": [{"const": "CARD"}, {"const": "WIRE"}],
              "not": {"const": "CASH"},
              "if": {"const": "CARD"},
              "then": {"maxLength": 4},
              "else": {"minLength": 4}
            }
          }
        }
        """);

    SchemaSnapshot snapshot = assertDoesNotThrow(() -> engine.schemaLoader().loadSchema(schema));

    assertEquals("1", snapshot.fields().get("quantity").constraints().get("multipleOf"));
    assertTrue(snapshot.schemaRestrictions().containsKey("metadata patternProperties"));
    assertTrue(snapshot.schemaRestrictions().containsKey("tags contains"));
    assertTrue(snapshot.schemaRestrictions().containsKey("closedTags items"));
    assertEquals("any", snapshot.fields().get("openExtension").type());
    assertEquals("never", snapshot.fields().get("disabledExtension").type());
    assertEquals("anyof", snapshot.fields().get("choice").type());
    assertTrue(snapshot.schemaRestrictions().containsKey("payment anyOf"));
  }

  @Test
  void failsCompatibilityWhenAConservativelyTrackedSchemaRestrictionChanges() throws IOException {
    Path base = writeSchema(
        "base.json",
        "{" +
            "\"type\":\"object\",\"properties\":{\"metadata\":{" +
            "\"type\":\"object\",\"additionalProperties\":true}}}");
    Path candidate = writeSchema(
        "candidate.json",
        "{" +
            "\"type\":\"object\",\"properties\":{\"metadata\":{" +
            "\"type\":\"object\",\"additionalProperties\":false}}}");

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream().anyMatch(
        message -> message.contains("Schema restriction added or changed: metadata additionalProperties")));
  }

  @Test
  void failsCompatibilityWhenObjectAndArrayCardinalityConstraintsTighten() throws IOException {
    Path base = writeSchema(
        "base.json",
        "{\"type\":\"object\",\"properties\":{" +
            "\"profile\":{\"type\":\"object\",\"minProperties\":1,\"maxProperties\":10}," +
            "\"tags\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"},\"minContains\":1,\"maxContains\":5}}}");
    Path candidate = writeSchema(
        "candidate.json",
        "{\"type\":\"object\",\"properties\":{" +
            "\"profile\":{\"type\":\"object\",\"minProperties\":2,\"maxProperties\":8}," +
            "\"tags\":{\"type\":\"array\",\"contains\":{\"type\":\"string\"},\"minContains\":2,\"maxContains\":4}}}");

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream().anyMatch(
        message -> message.contains("Constraint tightened: profile.minProperties (1 -> 2)")));
    assertTrue(result.breakingChanges().stream().anyMatch(
        message -> message.contains("Constraint tightened: profile.maxProperties (10 -> 8)")));
    assertTrue(result.breakingChanges().stream().anyMatch(
        message -> message.contains("Constraint tightened: tags.minContains (1 -> 2)")));
    assertTrue(result.breakingChanges().stream().anyMatch(
        message -> message.contains("Constraint tightened: tags.maxContains (5 -> 4)")));
  }

  @Test
  void rejectsMalformedOrUnsupportedCoreSchemasInsteadOfSilentlyIgnoringThem() throws IOException {
    Path malformedDependency = writeSchema(
        "malformed-dependency.json",
        "{" +
            "\"type\":\"object\",\"properties\":{\"metadata\":{" +
            "\"type\":\"object\",\"dependentRequired\":{\"country\":\"postalCode\"}}}}");
    Path dynamicReference = writeSchema(
        "dynamic-reference.json",
        "{" +
            "\"type\":\"object\",\"properties\":{\"id\":{\"$dynamicRef\":\"#identifier\"}}}");

    assertThrows(SchemaValidationException.class, () -> engine.schemaLoader().loadSchema(malformedDependency));
    assertThrows(SchemaValidationException.class, () -> engine.schemaLoader().loadSchema(dynamicReference));
  }

  @Test
  void ignoresAnnotationOnlyChangesForCompatibility() throws IOException {
    Path base = writeSchema(
        "base.json",
        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"title\":\"Old\"}}}");
    Path candidate = writeSchema(
        "candidate.json",
        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"title\":\"New\",\"description\":\"Changed\"}}}");

    assertEquals("Schema diff:" + System.lineSeparator() + "No semantic differences found.", engine.diff(base, candidate));
  }

  private String composedPurchaseOrderSchema(String optionalMethod, int minimumApprovalSteps) {
    String optionalBranch = optionalMethod == null
        ? ""
        : """
          ,
          {
            "type": "object",
            "properties": {"type": {"const": "%s"}},
            "required": ["type"]
          }""".formatted(optionalMethod);
    return """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string"},
            "approvalChain": {"type": "array"},
            "paymentTerms": {"$ref": "#/$defs/PaymentTerms"}
          },
          "allOf": [{
            "if": {"properties": {"status": {"const": "APPROVED"}}, "required": ["status"]},
            "then": {"required": ["approvalChain"], "properties": {"approvalChain": {"type": "array", "minItems": %d}}}
          }],
          "$defs": {
            "PaymentTerms": {
              "type": "object",
              "properties": {
                "method": {
                  "oneOf": [
                    {
                      "type": "object",
                      "properties": {
                        "type": {"const": "CREDIT_CARD"},
                        "cardLast4": {"type": "string", "pattern": "^[0-9]{4}$"}
                      },
                      "required": ["type", "cardLast4"]
                    }%s
                  ]
                }
              }
            }
          }
        }
        """.formatted(minimumApprovalSteps, optionalBranch);
  }

  private Path writeSchema(String fileName, String json) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, json);
    return path;
  }
}
